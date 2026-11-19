package ai.inquery.server.domain.core.catalog;

import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pre-execution guard against summing columns that must not be summed.
 *
 * <p>A column catalogued as {@code distinct} (a distinct count) or
 * {@code non_additive} (ratio, average, point-in-time value) is only
 * meaningful at its table's grain. Summing it across rows that differ in a
 * grain column (a date, a region, a segment...) double counts or produces a
 * meaningless number, without any error. This guard rejects such SQL before
 * it runs, so the SQL writer can regenerate it.
 *
 * <p>A {@code SUM(col)} on such a column passes when every grain column of
 * its table is either in the GROUP BY or fixed to one value in the WHERE, or
 * when it is the numerator of an average ({@code SUM(col) / COUNT(DISTINCT
 * ...)}). There is deliberately no override: every SQL checked here is
 * written by a model, and an override the model can see is one it will use
 * to silence the check instead of fixing the query.
 *
 * <p>Grain columns are the table's non-numeric columns (dates, timestamps,
 * strings, booleans). This is a safety check on catalog metadata, not an
 * analytical judgement: it never decides what the user should ask.
 */
@Slf4j
@Component
public class AggregationGuard {

    @Autowired(required = false)
    private DataCatalogService dataCatalogService;

    /** Columns of one table as the guard needs them. */
    public record TableShape(Map<String, ColumnAggregation> aggregations, Set<String> grainColumns,
                             Set<String> dateColumns) {
        public TableShape(Map<String, ColumnAggregation> aggregations, Set<String> grainColumns) {
            this(aggregations, grainColumns, Set.of());
        }

        public static TableShape empty() {
            return new TableShape(Map.of(), Set.of(), Set.of());
        }
    }

    /** A date column of a base table that a query uses, with the table name as written in the SQL. */
    public record DateColumnUse(String tableInSql, String tableKey, String column) {
    }

    // ------------------------------------------------------------------
    // Catalog-backed entry point
    // ------------------------------------------------------------------

    /**
     * Violations for {@code sql}, resolving tables against the catalog of the
     * given data source. Empty when nothing is wrong or nothing is catalogued.
     */
    public List<String> check(String sql, Long dataSourceId, String databaseName, String schemaName) {
        if (dataCatalogService == null || dataSourceId == null) return List.of();
        Map<String, TableShape> cache = new HashMap<>();
        return check(sql, qualified -> cache.computeIfAbsent(qualified,
                q -> loadShape(q, dataSourceId, databaseName, schemaName)));
    }

    /** Catalog aggregation kinds of the plain columns a SELECT outputs (by output name). */
    public Map<String, ColumnAggregation> outputColumnKinds(String sql, Long dataSourceId,
                                                           String databaseName, String schemaName) {
        if (dataCatalogService == null || dataSourceId == null) return Map.of();
        Map<String, TableShape> cache = new HashMap<>();
        return outputColumnKinds(sql, qualified -> cache.computeIfAbsent(qualified,
                q -> loadShape(q, dataSourceId, databaseName, schemaName)));
    }

    /**
     * Date / timestamp columns of the query's base tables that the SQL text
     * references (filters, grouping or selection), at most {@code limit}.
     */
    public List<DateColumnUse> dateColumnsUsed(String sql, Long dataSourceId, String databaseName,
                                               String schemaName, int limit) {
        List<DateColumnUse> out = new ArrayList<>();
        if (dataCatalogService == null || dataSourceId == null || sql == null || sql.isBlank()) return out;
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(stripTrailingSemicolon(sql));
        } catch (Exception | Error e) {
            return out;
        }
        if (!(st instanceof Select select)) return out;
        Set<String> cteNames = new HashSet<>();
        List<PlainSelect> selects = new ArrayList<>();
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                cteNames.add(w.getName().toLowerCase(Locale.ROOT));
                if (w.getSubSelect() != null) collectSelects(w.getSubSelect().getSelectBody(), selects);
            }
        }
        collectSelects(select.getSelectBody(), selects);
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (PlainSelect ps : selects) {
            List<FromItem> items = new ArrayList<>();
            if (ps.getFromItem() != null) items.add(ps.getFromItem());
            if (ps.getJoins() != null) for (Join j : ps.getJoins()) items.add(j.getRightItem());
            for (FromItem item : items) {
                if (!(item instanceof Table t) || t.getName() == null) continue;
                if (cteNames.contains(unquote(t.getName()))) continue;
                String key = tableKey(t);
                TableShape shape = loadShape(key, dataSourceId, databaseName, schemaName);
                for (String col : shape.dateColumns()) {
                    if (!Pattern.compile("\\b" + Pattern.quote(col) + "\\b").matcher(lowerSql).find()) continue;
                    if (!seen.add(key + "." + col)) continue;
                    String inSql = t.getFullyQualifiedName();
                    out.add(new DateColumnUse(inSql, key, col));
                    if (out.size() >= limit) return out;
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private TableShape loadShape(String qualifiedName, Long dataSourceId, String databaseName, String schemaName) {
        try {
            String[] parts = qualifiedName.split("\\.");
            String table = parts[parts.length - 1];
            String schema = parts.length >= 2 ? parts[parts.length - 2] : schemaName;
            String database = parts.length >= 3 ? parts[parts.length - 3] : databaseName;
            if (database == null || database.isBlank()) {
                // The chat may run without a selected database: take it from the catalog.
                database = catalogDatabase(dataSourceId, schema, table);
            }
            TableQueryParam param = new TableQueryParam();
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(database);
            param.setSchemaName(schema);
            param.setTableName(table);
            DataResult<Map<String, Object>> result = dataCatalogService.queryCatalog(param);
            if (result == null || result.getData() == null) return TableShape.empty();
            Object cols = result.getData().get("columns");
            if (!(cols instanceof List<?> list)) return TableShape.empty();
            Map<String, ColumnAggregation> aggs = new HashMap<>();
            Set<String> grain = new HashSet<>();
            Set<String> dates = new HashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> col)) continue;
                String name = String.valueOf(col.get("name"));
                if (name.contains(".")) continue; // nested struct fields are not grain keys
                String key = name.toLowerCase(Locale.ROOT);
                ColumnAggregation agg = ColumnAggregation.fromCode((String) col.get("aggregation"));
                if (agg != ColumnAggregation.UNKNOWN) aggs.put(key, agg);
                Object type = col.get("columnType");
                if (agg == ColumnAggregation.UNKNOWN && isGrainType(type == null ? "" : type.toString())) {
                    grain.add(key);
                }
                if (FreshnessNotes.isDateType(type == null ? null : type.toString())) {
                    dates.add(key);
                }
            }
            return new TableShape(aggs, grain, dates);
        } catch (Exception e) {
            log.debug("[AggregationGuard] catalog lookup failed for {}: {}", qualifiedName, e.getMessage());
            return TableShape.empty();
        }
    }

    /**
     * Live columns (name -> type, in table order) of a table as written in SQL,
     * or an empty map when the table cannot be found.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> liveColumns(String tableAsWritten, Long dataSourceId, String databaseName,
                                           String schemaName) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dataCatalogService == null || dataSourceId == null || tableAsWritten == null) return out;
        try {
            String[] parts = tableAsWritten.replaceAll("[`\"\\[\\]]", "").split("\\.");
            String table = parts[parts.length - 1];
            String schema = parts.length >= 2 ? parts[parts.length - 2] : schemaName;
            String database = parts.length >= 3 ? parts[parts.length - 3] : databaseName;
            if (database == null || database.isBlank()) database = catalogDatabase(dataSourceId, schema, table);
            TableQueryParam param = new TableQueryParam();
            param.setDataSourceId(dataSourceId);
            param.setDatabaseName(database);
            param.setSchemaName(schema);
            param.setTableName(table);
            DataResult<Map<String, Object>> result = dataCatalogService.queryCatalog(param);
            if (result == null || result.getData() == null) return out;
            if (result.getData().get("columns") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> col && col.get("name") != null) {
                        Object type = col.get("columnType");
                        out.put(String.valueOf(col.get("name")), type == null ? "" : type.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[AggregationGuard] live columns failed for {}: {}", tableAsWritten, e.getMessage());
        }
        return out;
    }

    /** schema.table names catalogued for the data source (for "did you mean" suggestions). */
    public static List<String> catalogTableNames(Long dataSourceId) {
        List<String> out = new ArrayList<>();
        try {
            var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                    ai.inquery.server.domain.repository.entity.DataCatalogTableDO>()
                    .eq(ai.inquery.server.domain.repository.entity.DataCatalogTableDO::getDataSourceId, dataSourceId);
            for (var row : ai.inquery.server.domain.repository.Dbutils.getMapper(
                    ai.inquery.server.domain.repository.mapper.DataCatalogTableMapper.class).selectList(query)) {
                String name = (row.getSchemaName() == null || row.getSchemaName().isBlank() ? "" : row.getSchemaName() + ".")
                        + row.getTableName();
                if (!out.contains(name)) out.add(name);
            }
        } catch (Exception e) {
            log.debug("[AggregationGuard] catalog table list failed: {}", e.getMessage());
        }
        return out;
    }

    /** The single catalogued database holding schema.table, or null when unknown or ambiguous. */
    public static String catalogDatabase(Long dataSourceId, String schema, String table) {
        try {
            var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                    ai.inquery.server.domain.repository.entity.DataCatalogTableDO>()
                    .eq(ai.inquery.server.domain.repository.entity.DataCatalogTableDO::getDataSourceId, dataSourceId)
                    .apply("LOWER(table_name) = {0}", table.toLowerCase(Locale.ROOT));
            if (schema != null && !schema.isBlank()) {
                query.apply("LOWER(schema_name) = {0}", schema.toLowerCase(Locale.ROOT));
            }
            Set<String> dbs = new HashSet<>();
            for (var row : ai.inquery.server.domain.repository.Dbutils.getMapper(
                    ai.inquery.server.domain.repository.mapper.DataCatalogTableMapper.class).selectList(query)) {
                if (row.getDatabaseName() != null) dbs.add(row.getDatabaseName());
            }
            return dbs.size() == 1 ? dbs.iterator().next() : null;
        } catch (Exception e) {
            log.debug("[AggregationGuard] catalog database lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static final Pattern NUMERIC_TYPE = Pattern.compile(
            "(?i).*(int|num|dec|float|double|real|money|number|serial).*");
    private static final Pattern NESTED_TYPE = Pattern.compile("(?i).*(struct|record|array|json|map|geography|bytes|blob).*");

    static boolean isGrainType(String type) {
        if (type == null || type.isBlank()) return false;
        return !NUMERIC_TYPE.matcher(type).matches() && !NESTED_TYPE.matcher(type).matches();
    }

    // ------------------------------------------------------------------
    // Pure check
    // ------------------------------------------------------------------

    /**
     * Violations for {@code sql}. {@code lookup} maps a table name as written
     * in the SQL (possibly qualified, lower-cased, unquoted) to its shape.
     */
    public static List<String> check(String sql, java.util.function.Function<String, TableShape> lookup) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(stripTrailingSemicolon(sql));
        } catch (Exception | Error e) {
            log.debug("[AggregationGuard] SQL not parsed, guard skipped: {}", e.getMessage());
            return List.of();
        }
        if (!(st instanceof Select select)) return List.of();

        Set<String> cteNames = new HashSet<>();
        List<PlainSelect> selects = new ArrayList<>();
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                cteNames.add(w.getName().toLowerCase(Locale.ROOT));
                if (w.getSubSelect() != null) collectSelects(w.getSubSelect().getSelectBody(), selects);
            }
        }
        collectSelects(select.getSelectBody(), selects);

        LinkedHashSet<String> violations = new LinkedHashSet<>();
        for (PlainSelect ps : selects) {
            checkSelect(ps, cteNames, lookup, violations);
        }
        return new ArrayList<>(violations);
    }

    private static void collectSelects(SelectBody body, List<PlainSelect> out) {
        if (body instanceof PlainSelect ps) {
            out.add(ps);
            List<FromItem> items = new ArrayList<>();
            if (ps.getFromItem() != null) items.add(ps.getFromItem());
            if (ps.getJoins() != null) for (Join j : ps.getJoins()) items.add(j.getRightItem());
            for (FromItem item : items) {
                if (item instanceof SubSelect sub) collectSelects(sub.getSelectBody(), out);
            }
        } else if (body instanceof SetOperationList sol && sol.getSelects() != null) {
            for (SelectBody b : sol.getSelects()) collectSelects(b, out);
        }
    }

    /** Base tables of one SELECT: alias (or table name) -> table key. */
    private static Map<String, String> baseTables(PlainSelect ps, Set<String> cteNames) {
        Map<String, String> out = new LinkedHashMap<>();
        List<FromItem> items = new ArrayList<>();
        if (ps.getFromItem() != null) items.add(ps.getFromItem());
        if (ps.getJoins() != null) for (Join j : ps.getJoins()) items.add(j.getRightItem());
        for (FromItem item : items) {
            if (!(item instanceof Table t) || t.getName() == null) continue;
            String key = tableKey(t);
            String simple = unquote(t.getName());
            if (cteNames.contains(simple)) continue;
            String alias = t.getAlias() != null ? unquote(t.getAlias().getName()) : simple;
            out.put(alias, key);
            out.putIfAbsent(simple, key);
        }
        return out;
    }

    private static void checkSelect(PlainSelect ps, Set<String> cteNames,
                                    java.util.function.Function<String, TableShape> lookup,
                                    Set<String> violations) {
        Map<String, String> tables = baseTables(ps, cteNames);
        if (tables.isEmpty()) return;

        List<SumCall> sums = new ArrayList<>();
        for (SelectItem item : ps.getSelectItems()) {
            if (item instanceof SelectExpressionItem sei) findSums(sei.getExpression(), false, sums);
        }
        if (ps.getHaving() != null) findSums(ps.getHaving(), false, sums);
        if (sums.isEmpty()) return;

        Set<String> covered = coveredColumns(ps);

        for (SumCall sum : sums) {
            Column col = sum.column;
            String colKey = unquote(col.getColumnName());
            String tableKey = resolveTable(col, tables, lookup);
            if (tableKey == null) continue;
            TableShape shape = lookup.apply(tableKey);
            ColumnAggregation agg = shape.aggregations().getOrDefault(colKey, ColumnAggregation.UNKNOWN);
            if (!agg.isNonSummable() || sum.averageNumerator) continue;

            String kind = agg == ColumnAggregation.DISTINCT
                    ? "a distinct count (rows overlap, so a sum double counts)"
                    : "a ratio, average or point-in-time value (a sum of it is meaningless)";
            if (sum.window) {
                violations.add("`" + colKey + "` in `" + tableKey + "` is " + kind
                        + ". A running/window SUM over it is not valid. Query a table at the wanted grain, "
                        + "or recompute the value from additive columns.");
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String g : shape.grainColumns()) {
                if (!covered.contains(g)) missing.add(g);
            }
            if (missing.isEmpty()) continue;
            violations.add("`" + colKey + "` in `" + tableKey + "` is " + kind
                    + ". SUM(" + colKey + ") here adds it across " + String.join(", ", missing)
                    + " (not in GROUP BY and not fixed to one value in WHERE). Group by those columns, "
                    + "query a table already at the wanted grain, or report an average "
                    + "(SUM(" + colKey + ") / COUNT(DISTINCT <date column>)). For a distinct count over the whole "
                    + "range, count distinct entities from a table that has one row per entity event.");
        }
    }

    private record SumCall(Column column, boolean window, boolean averageNumerator) {
    }

    /** Collects SUM(column) calls; marks those used as the numerator of SUM(x)/COUNT(DISTINCT y). */
    private static void findSums(Expression expr, boolean averageNumerator, List<SumCall> out) {
        if (expr == null) return;
        if (expr instanceof Division d) {
            boolean avg = isCountDistinct(d.getRightExpression());
            findSums(d.getLeftExpression(), avg, out);
            findSums(d.getRightExpression(), false, out);
            return;
        }
        if (expr instanceof AnalyticExpression a) {
            if ("SUM".equalsIgnoreCase(a.getName())) {
                Column c = innerColumn(a.getExpression());
                if (c != null) out.add(new SumCall(c, true, false));
            }
            return;
        }
        if (expr instanceof Function f) {
            String name = f.getName() == null ? "" : f.getName().toUpperCase(Locale.ROOT);
            List<Expression> params = params(f);
            if ("SUM".equals(name) && params.size() == 1 && !f.isDistinct()) {
                Column c = innerColumn(params.get(0));
                if (c != null) {
                    out.add(new SumCall(c, false, averageNumerator));
                    return;
                }
            }
            // SAFE_DIVIDE(SUM(x), COUNT(DISTINCT d)) is also an average.
            boolean safeDivide = ("SAFE_DIVIDE".equals(name) || "DIV".equals(name) || "IEEE_DIVIDE".equals(name))
                    && params.size() == 2 && isCountDistinct(params.get(1));
            for (int i = 0; i < params.size(); i++) {
                findSums(params.get(i), safeDivide && i == 0, out);
            }
            return;
        }
        if (expr instanceof Parenthesis p) {
            findSums(p.getExpression(), averageNumerator, out);
        } else if (expr instanceof SignedExpression s) {
            findSums(s.getExpression(), averageNumerator, out);
        } else if (expr instanceof CastExpression c) {
            findSums(c.getLeftExpression(), averageNumerator, out);
        } else if (expr instanceof BinaryExpression b) {
            findSums(b.getLeftExpression(), false, out);
            findSums(b.getRightExpression(), false, out);
        } else if (expr instanceof CaseExpression c) {
            if (c.getWhenClauses() != null) {
                for (WhenClause w : c.getWhenClauses()) findSums(w.getThenExpression(), false, out);
            }
            findSums(c.getElseExpression(), false, out);
        }
    }

    private static List<Expression> params(Function f) {
        if (f.getParameters() == null || f.getParameters().getExpressions() == null) return List.of();
        return f.getParameters().getExpressions();
    }

    private static boolean isCountDistinct(Expression e) {
        Expression x = unwrap(e);
        if (x instanceof Function f) {
            String name = f.getName() == null ? "" : f.getName().toUpperCase(Locale.ROOT);
            return ("COUNT".equals(name) && f.isDistinct()) || "APPROX_COUNT_DISTINCT".equals(name);
        }
        return false;
    }

    /** The column inside SUM(...), looking through casts and null-handling wrappers. */
    private static Column innerColumn(Expression e) {
        Expression x = unwrap(e);
        if (x instanceof Column c) return c;
        if (x instanceof Function f) {
            String name = f.getName() == null ? "" : f.getName().toUpperCase(Locale.ROOT);
            List<Expression> p = params(f);
            if (!p.isEmpty() && Set.of("COALESCE", "IFNULL", "NVL", "ISNULL", "CAST", "SAFE_CAST").contains(name)) {
                return innerColumn(p.get(0));
            }
        }
        return null;
    }

    private static Expression unwrap(Expression e) {
        Expression x = e;
        while (true) {
            if (x instanceof Parenthesis p) x = p.getExpression();
            else if (x instanceof CastExpression c) x = c.getLeftExpression();
            else return x;
        }
    }

    /** GROUP BY plain columns plus columns pinned to a single value in WHERE. */
    private static Set<String> coveredColumns(PlainSelect ps) {
        Set<String> covered = new HashSet<>();
        Map<String, Expression> aliasToExpr = new HashMap<>();
        List<SelectItem> items = ps.getSelectItems();
        for (SelectItem item : items) {
            if (item instanceof SelectExpressionItem sei && sei.getAlias() != null) {
                aliasToExpr.put(unquote(sei.getAlias().getName()), sei.getExpression());
            }
        }
        if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
            for (Object o : ps.getGroupBy().getGroupByExpressionList().getExpressions()) {
                Expression g = (Expression) o;
                String text = g.toString().trim();
                if (text.matches("\\d+")) {
                    int idx = Integer.parseInt(text) - 1;
                    if (idx >= 0 && idx < items.size() && items.get(idx) instanceof SelectExpressionItem sei) {
                        g = sei.getExpression();
                    }
                }
                if (g instanceof Column c) {
                    String name = unquote(c.getColumnName());
                    Expression aliased = aliasToExpr.get(name);
                    if (aliased instanceof Column ac) {
                        covered.add(unquote(ac.getColumnName()));
                    } else if (aliased == null) {
                        covered.add(name);
                    }
                }
            }
        }
        addPinned(ps.getWhere(), covered);
        return covered;
    }

    private static void addPinned(Expression where, Set<String> covered) {
        if (where == null) return;
        if (where instanceof AndExpression and) {
            addPinned(and.getLeftExpression(), covered);
            addPinned(and.getRightExpression(), covered);
        } else if (where instanceof Parenthesis p) {
            addPinned(p.getExpression(), covered);
        } else if (where instanceof EqualsTo eq) {
            if (unwrap(eq.getLeftExpression()) instanceof Column c && !(unwrap(eq.getRightExpression()) instanceof Column)) {
                covered.add(unquote(c.getColumnName()));
            } else if (unwrap(eq.getRightExpression()) instanceof Column c
                    && !(unwrap(eq.getLeftExpression()) instanceof Column)) {
                covered.add(unquote(c.getColumnName()));
            }
        } else if (where instanceof InExpression in && !in.isNot()
                && unwrap(in.getLeftExpression()) instanceof Column c
                && in.getRightItemsList() instanceof ExpressionList list
                && list.getExpressions() != null && list.getExpressions().size() == 1) {
            covered.add(unquote(c.getColumnName()));
        }
    }

    private static String resolveTable(Column col, Map<String, String> tables,
                                       java.util.function.Function<String, TableShape> lookup) {
        if (col.getTable() != null && col.getTable().getName() != null) {
            return tables.get(unquote(col.getTable().getName()));
        }
        Set<String> distinct = new LinkedHashSet<>(tables.values());
        if (distinct.size() == 1) return distinct.iterator().next();
        String colKey = unquote(col.getColumnName());
        for (String t : distinct) {
            if (lookup.apply(t).aggregations().containsKey(colKey)) return t;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Output column kinds (used by result interpretation)
    // ------------------------------------------------------------------

    /**
     * Catalog kinds for output columns that are plain column references to a
     * catalogued base table, keyed by output name (alias or column name).
     */
    public static Map<String, ColumnAggregation> outputColumnKinds(String sql,
                                                                   java.util.function.Function<String, TableShape> lookup) {
        Map<String, ColumnAggregation> out = new HashMap<>();
        if (sql == null || sql.isBlank()) return out;
        try {
            Statement st = CCJSqlParserUtil.parse(stripTrailingSemicolon(sql));
            if (!(st instanceof Select select)) return out;
            Set<String> cteNames = new HashSet<>();
            if (select.getWithItemsList() != null) {
                for (WithItem w : select.getWithItemsList()) cteNames.add(w.getName().toLowerCase(Locale.ROOT));
            }
            SelectBody body = select.getSelectBody();
            while (body instanceof SetOperationList sol && sol.getSelects() != null && !sol.getSelects().isEmpty()) {
                body = sol.getSelects().get(0);
            }
            if (!(body instanceof PlainSelect ps)) return out;
            Map<String, String> tables = baseTables(ps, cteNames);
            for (SelectItem item : ps.getSelectItems()) {
                if (!(item instanceof SelectExpressionItem sei)) continue;
                Expression e = unwrap(sei.getExpression());
                if (!(e instanceof Column c)) continue;
                String tableKey = resolveTable(c, tables, lookup);
                if (tableKey == null) continue;
                ColumnAggregation agg = lookup.apply(tableKey).aggregations()
                        .getOrDefault(unquote(c.getColumnName()), ColumnAggregation.UNKNOWN);
                if (agg == ColumnAggregation.UNKNOWN) continue;
                String name = sei.getAlias() != null ? sei.getAlias().getName() : c.getColumnName();
                out.put(unquote(name), agg);
            }
        } catch (Exception | Error e) {
            log.debug("[AggregationGuard] output kinds skipped: {}", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    private static String tableKey(Table t) {
        List<String> parts = new ArrayList<>();
        if (t.getDatabase() != null && t.getDatabase().getDatabaseName() != null && t.getSchemaName() != null) {
            parts.add(unquote(t.getDatabase().getDatabaseName()));
        }
        if (t.getSchemaName() != null) parts.add(unquote(t.getSchemaName()));
        parts.add(unquote(t.getName()));
        return String.join(".", parts);
    }

    private static String unquote(String name) {
        String s = name == null ? "" : name.trim();
        if (s.length() >= 2 && (s.startsWith("`") || s.startsWith("\"") || s.startsWith("["))) {
            s = s.substring(1, s.length() - 1);
        }
        // A fully backticked BigQuery path (`project.dataset.table`) keeps only the table part.
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }
}
