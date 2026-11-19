package ai.inquery.server.domain.core.catalog;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a numeric column may be aggregated across rows.
 *
 * <p>Derived deterministically from the SQL expression that produces the
 * column: {@code SUM(...)} / {@code COUNT(...)} are additive,
 * {@code COUNT(DISTINCT ...)} is a distinct count (rows overlap, a sum
 * double counts), and averages, ratios, extremes and window/running values
 * are non-additive. Plain column references carry no evidence and are
 * {@link #UNKNOWN} unless they can be traced through CTEs or subqueries to
 * an aggregate.
 */
@Slf4j
public enum ColumnAggregation {
    ADDITIVE("additive"),
    DISTINCT("distinct"),
    NON_ADDITIVE("non_additive"),
    UNKNOWN(null);

    private final String code;

    ColumnAggregation(String code) {
        this.code = code;
    }

    /** Value stored in {@code data_catalog_column.aggregation}; null for UNKNOWN. */
    public String code() {
        return code;
    }

    public static ColumnAggregation fromCode(String code) {
        if (code == null || code.isBlank()) return UNKNOWN;
        for (ColumnAggregation a : values()) {
            if (a.code != null && a.code.equalsIgnoreCase(code.trim())) return a;
        }
        return UNKNOWN;
    }

    /** True for columns whose values must never be summed across rows. */
    public boolean isNonSummable() {
        return this == DISTINCT || this == NON_ADDITIVE;
    }

    // ------------------------------------------------------------------
    // Expression classification
    // ------------------------------------------------------------------

    private static final Set<String> ADDITIVE_FUNCS = Set.of("SUM", "COUNT", "COUNTIF", "COUNT_IF", "COUNT_BIG");
    private static final Set<String> DISTINCT_FUNCS = Set.of(
            "APPROX_COUNT_DISTINCT", "APPROX_DISTINCT", "HLL_COUNT.MERGE", "HLL_COUNT.EXTRACT",
            "NDV", "UNIQ", "UNIQEXACT", "COUNT_DISTINCT");
    private static final Set<String> NON_ADDITIVE_FUNCS = Set.of(
            "AVG", "SAFE_DIVIDE", "DIV", "IEEE_DIVIDE", "MEDIAN", "PERCENTILE_CONT", "PERCENTILE_DISC",
            "APPROX_QUANTILES", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE", "VAR_POP", "VAR_SAMP",
            "MIN", "MAX", "ANY_VALUE", "CORR");
    private static final Set<String> PASS_THROUGH_FUNCS = Set.of(
            "ROUND", "CAST", "SAFE_CAST", "COALESCE", "IFNULL", "NVL", "ISNULL", "ABS", "TRUNC", "TRUNCATE",
            "FLOOR", "CEIL", "CEILING", "TO_NUMBER", "CONVERT");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");

    /** Classifies one SELECT expression without resolving column references. */
    public static ColumnAggregation classify(Expression expr) {
        return classify(expr, null);
    }

    private static ColumnAggregation classify(Expression expr, Resolver resolver) {
        if (expr == null) return UNKNOWN;
        if (expr instanceof Parenthesis p) return classify(p.getExpression(), resolver);
        if (expr instanceof SignedExpression s) return classify(s.getExpression(), resolver);
        if (expr instanceof CastExpression c) return classify(c.getLeftExpression(), resolver);
        if (expr instanceof AnalyticExpression) return NON_ADDITIVE;
        if (expr instanceof Division) return NON_ADDITIVE;
        if (expr instanceof Function f) {
            String name = f.getName() == null ? "" : f.getName().toUpperCase(Locale.ROOT);
            if (f.isDistinct() || DISTINCT_FUNCS.contains(name)) return DISTINCT;
            if (ADDITIVE_FUNCS.contains(name)) return ADDITIVE;
            if (NON_ADDITIVE_FUNCS.contains(name)) return NON_ADDITIVE;
            if (PASS_THROUGH_FUNCS.contains(name) && f.getParameters() != null
                    && f.getParameters().getExpressions() != null
                    && !f.getParameters().getExpressions().isEmpty()) {
                return classify(f.getParameters().getExpressions().get(0), resolver);
            }
            return UNKNOWN;
        }
        if (expr instanceof Addition || expr instanceof Subtraction) {
            BinaryExpression b = (BinaryExpression) expr;
            ColumnAggregation l = classify(b.getLeftExpression(), resolver);
            ColumnAggregation r = classify(b.getRightExpression(), resolver);
            if (l == ADDITIVE && (r == ADDITIVE || isConstant(b.getRightExpression()))) return ADDITIVE;
            if (r == ADDITIVE && isConstant(b.getLeftExpression())) return ADDITIVE;
            return l == UNKNOWN || r == UNKNOWN ? UNKNOWN : NON_ADDITIVE;
        }
        if (expr instanceof Multiplication m) {
            // Scaling by a constant keeps the kind (e.g. SUM(x) * 100).
            if (isConstant(m.getRightExpression())) return classify(m.getLeftExpression(), resolver);
            if (isConstant(m.getLeftExpression())) return classify(m.getRightExpression(), resolver);
            return UNKNOWN;
        }
        if (expr instanceof Column c && resolver != null) {
            return resolver.resolve(c.getColumnName());
        }
        return UNKNOWN;
    }

    private static boolean isConstant(Expression e) {
        return e != null && NUMERIC.matcher(e.toString().trim()).matches();
    }

    // ------------------------------------------------------------------
    // Model SQL (lineage source query) -> output column kinds
    // ------------------------------------------------------------------

    private static final Pattern JINJA_CONFIG = Pattern.compile("\\{\\{\\s*config\\s*\\(.*?\\)\\s*}}", Pattern.DOTALL);
    private static final Pattern JINJA_SOURCE = Pattern.compile(
            "(?i)\\b(from|join)(\\s+)\\{\\{.*?}}", Pattern.DOTALL);
    private static final Pattern JINJA_EXPR = Pattern.compile("\\{\\{.*?}}", Pattern.DOTALL);
    private static final Pattern JINJA_STMT = Pattern.compile("\\{%.*?%}", Pattern.DOTALL);
    private static final Pattern JINJA_COMMENT = Pattern.compile("\\{#.*?#}", Pattern.DOTALL);
    private static final int MAX_DEPTH = 12;

    /**
     * Classifies every output column of a model's SQL (for example a dbt
     * model's source query). Columns selected as plain references are traced
     * through CTEs and subqueries. Jinja is neutralised first (config blocks
     * and control tags removed, ref/source calls turned into a table name) so
     * templated models still parse.
     *
     * @return lower-cased output column name to aggregation; columns that
     * stay {@link #UNKNOWN} are omitted. Empty when the SQL cannot be parsed.
     */
    public static Map<String, ColumnAggregation> fromModelSql(String sql) {
        Map<String, ColumnAggregation> out = new LinkedHashMap<>();
        if (sql == null || sql.isBlank()) return out;
        String cleaned = JINJA_COMMENT.matcher(sql).replaceAll(" ");
        cleaned = JINJA_STMT.matcher(cleaned).replaceAll(" ");
        cleaned = JINJA_CONFIG.matcher(cleaned).replaceAll(" ");
        // {{ ref(...) }} / {{ source(...) }} in a FROM/JOIN become a table name;
        // any other expression (e.g. {{ var(...) }}) becomes a NULL literal.
        cleaned = JINJA_SOURCE.matcher(cleaned).replaceAll("$1$2jinja_source");
        cleaned = JINJA_EXPR.matcher(cleaned).replaceAll("NULL");
        cleaned = cleaned.trim();
        while (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        try {
            Statement st = CCJSqlParserUtil.parse(cleaned);
            if (!(st instanceof Select select)) return out;
            Map<String, SelectBody> ctes = new HashMap<>();
            if (select.getWithItemsList() != null) {
                for (WithItem w : select.getWithItemsList()) {
                    if (w.getSubSelect() != null && w.getSubSelect().getSelectBody() != null) {
                        ctes.put(w.getName().toLowerCase(Locale.ROOT), w.getSubSelect().getSelectBody());
                    }
                }
            }
            PlainSelect ps = firstPlain(select.getSelectBody());
            if (ps == null) return out;
            for (Map.Entry<String, ColumnAggregation> e : outputs(ps, ctes, 0).entrySet()) {
                if (e.getValue() != UNKNOWN) out.put(e.getKey(), e.getValue());
            }
        } catch (Exception | Error e) {
            log.debug("[ColumnAggregation] model SQL not parsed: {}", e.getMessage());
        }
        return out;
    }

    /** Output name -> kind for one SELECT, expanding {@code *} from its sources. */
    private static Map<String, ColumnAggregation> outputs(PlainSelect ps, Map<String, SelectBody> ctes, int depth) {
        Map<String, ColumnAggregation> out = new LinkedHashMap<>();
        if (depth > MAX_DEPTH) return out;
        Resolver resolver = new Resolver(ps, ctes, depth);
        for (SelectItem item : ps.getSelectItems()) {
            if (item instanceof AllColumns || item instanceof AllTableColumns) {
                for (Map<String, ColumnAggregation> src : resolver.sources()) {
                    src.forEach(out::putIfAbsent);
                }
            } else if (item instanceof SelectExpressionItem sei) {
                Expression expr = sei.getExpression();
                String name = sei.getAlias() != null ? sei.getAlias().getName()
                        : expr instanceof Column c ? c.getColumnName() : null;
                if (name == null) continue;
                out.put(stripQuotes(name), classify(expr, resolver));
            }
        }
        return out;
    }

    private static PlainSelect firstPlain(SelectBody body) {
        if (body instanceof PlainSelect ps) return ps;
        if (body instanceof SetOperationList sol && sol.getSelects() != null && !sol.getSelects().isEmpty()) {
            return firstPlain(sol.getSelects().get(0));
        }
        return null;
    }

    private static String stripQuotes(String name) {
        String s = name.trim();
        if (s.length() >= 2 && (s.startsWith("`") || s.startsWith("\"") || s.startsWith("["))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** Resolves plain column references against a SELECT's FROM/JOIN sources. */
    private static final class Resolver {
        private final PlainSelect ps;
        private final Map<String, SelectBody> ctes;
        private final int depth;
        private List<Map<String, ColumnAggregation>> sources;

        Resolver(PlainSelect ps, Map<String, SelectBody> ctes, int depth) {
            this.ps = ps;
            this.ctes = ctes;
            this.depth = depth;
        }

        ColumnAggregation resolve(String column) {
            String key = stripQuotes(column);
            for (Map<String, ColumnAggregation> src : sources()) {
                ColumnAggregation a = src.get(key);
                if (a != null) return a;
            }
            return UNKNOWN;
        }

        List<Map<String, ColumnAggregation>> sources() {
            if (sources != null) return sources;
            sources = new ArrayList<>();
            List<FromItem> items = new ArrayList<>();
            if (ps.getFromItem() != null) items.add(ps.getFromItem());
            if (ps.getJoins() != null) {
                for (Join j : ps.getJoins()) items.add(j.getRightItem());
            }
            for (FromItem item : items) {
                SelectBody body = null;
                if (item instanceof Table t && t.getName() != null) {
                    body = ctes.get(t.getName().toLowerCase(Locale.ROOT));
                } else if (item instanceof SubSelect sub) {
                    body = sub.getSelectBody();
                }
                PlainSelect inner = firstPlain(body);
                if (inner != null) sources.add(outputs(inner, ctes, depth + 1));
            }
            return sources;
        }
    }
}
