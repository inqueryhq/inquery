package ai.inquery.server.web.api.controller.ai.interpret;

import ai.inquery.server.domain.core.catalog.ColumnAggregation;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic figures for result interpretation.
 *
 * <p>The interpretation LLM tends to compute totals, subtotals, shares and
 * period changes in its head while writing, and those hand-computed numbers
 * are the most common source of wrong figures. This class computes those
 * figures from the full result on the server so the model can quote them
 * instead of calculating, and afterwards checks which figures in the answer
 * cannot be traced back to the result or the precomputed block.
 *
 * <p>Whether a column may be summed across rows is decided from its SELECT
 * expression, not from its name: {@code SUM(...)} / {@code COUNT(...)} are
 * additive, {@code COUNT(DISTINCT ...)} is a distinct count (no total),
 * {@code AVG}, division and window aggregates are non-additive, and plain
 * column references are unknown (a stored column may itself be a distinct
 * count or a ratio), so no total is offered for them.
 */
@Slf4j
public final class ResultFigures {

    public static final int MAX_ROWS = 5000;
    private static final int MAX_SHARE_ROWS = 50;
    private static final int MAX_GROUP_VALUES = 30;
    private static final int MAX_BLOCK_CHARS = 8000;
    private static final int MAX_PAIRWISE_VALUES = 300;
    private static final int MAX_UNVERIFIED = 20;

    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\d{4}-\\d{2}(-\\d{2})?([ T].*)?$|^\\d{4}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$|^\\d{4}-W\\d{2}$");

    private ResultFigures() {
    }

    /** Result of {@link #compute}. */
    public static final class Summary {
        private final String block;
        private final List<BigDecimal> figures;

        Summary(String block, List<BigDecimal> figures) {
            this.block = block;
            this.figures = figures;
        }

        /** Prompt block, or empty string when nothing useful was computed. */
        public String block() {
            return block;
        }

        /** Every number written into the block (used by the figure check). */
        public List<BigDecimal> figures() {
            return figures;
        }
    }

    // ------------------------------------------------------------------
    // Precomputed figures
    // ------------------------------------------------------------------

    public static Summary compute(List<Map<String, Object>> rows, String sql, Integer totalRowCount) {
        return compute(rows, sql, totalRowCount, Map.of());
    }

    /**
     * @param catalogKinds catalog aggregation kinds by output column name, used
     *                     for columns whose SQL expression carries no evidence
     *                     (plain column references). SQL evidence wins.
     */
    public static Summary compute(List<Map<String, Object>> rows, String sql, Integer totalRowCount,
                                  Map<String, ColumnAggregation> catalogKinds) {
        if (rows == null || rows.isEmpty()) {
            return new Summary("", List.of());
        }
        List<Map<String, Object>> data = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
        List<String> headers = new ArrayList<>(data.get(0).keySet());

        SqlShape shape = analyzeSql(sql);
        if (catalogKinds != null) {
            for (Map.Entry<String, ColumnAggregation> e : catalogKinds.entrySet()) {
                String key = norm(e.getKey());
                if (shape.kinds.getOrDefault(key, ColumnAggregation.UNKNOWN) == ColumnAggregation.UNKNOWN) {
                    shape.kinds.put(key, e.getValue());
                }
            }
        }

        Map<String, List<BigDecimal>> numeric = new LinkedHashMap<>();
        List<String> dimensions = new ArrayList<>();
        for (String h : headers) {
            boolean grouped = shape.groupBy.contains(norm(h));
            List<BigDecimal> values = grouped ? null : numericColumn(data, h);
            if (values == null) {
                dimensions.add(h);
            } else {
                numeric.put(h, values);
            }
        }
        if (numeric.isEmpty()) {
            return new Summary("", List.of());
        }

        List<BigDecimal> figures = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int n = data.size();
        sb.append("Computed by the server over ").append(n).append(" result row").append(n == 1 ? "" : "s");
        if (totalRowCount != null && totalRowCount > n) {
            sb.append(" (the query returned ").append(totalRowCount)
                    .append(" rows; figures below cover only these ").append(n).append(")");
        }
        sb.append(".\n");

        List<String> labels = rowLabels(data, dimensions);

        sb.append("\nColumns:\n");
        for (Map.Entry<String, List<BigDecimal>> e : numeric.entrySet()) {
            String col = e.getKey();
            ColumnAggregation kind = shape.kinds.getOrDefault(norm(col), ColumnAggregation.UNKNOWN);
            List<BigDecimal> vals = e.getValue();
            sb.append("- ").append(col).append(" [").append(describe(kind)).append("]: ");
            List<String> parts = new ArrayList<>();
            List<BigDecimal> present = nonNull(vals);
            if (present.isEmpty()) {
                sb.append("all values are NULL\n");
                continue;
            }
            if (kind == ColumnAggregation.ADDITIVE) {
                BigDecimal total = sum(present);
                parts.add("total " + fmt(total, figures));
            }
            if (kind != ColumnAggregation.NON_ADDITIVE) {
                parts.add("average per row " + fmt(avg(present), figures));
            }
            int minIdx = argExtreme(vals, false);
            int maxIdx = argExtreme(vals, true);
            parts.add("min " + fmt(vals.get(minIdx), figures) + label(labels, minIdx));
            parts.add("max " + fmt(vals.get(maxIdx), figures) + label(labels, maxIdx));
            sb.append(String.join(", ", parts)).append("\n");
        }

        // Subtotals by a low-cardinality dimension when there are 2+ dimensions
        // (with a single dimension each value is already its own row).
        if (dimensions.size() >= 2) {
            for (String dim : dimensions) {
                Map<String, List<Integer>> groups = groupIndexes(data, dim);
                if (groups.size() < 2 || groups.size() > MAX_GROUP_VALUES || groups.size() == n) continue;
                for (Map.Entry<String, List<BigDecimal>> e : numeric.entrySet()) {
                    if (shape.kinds.getOrDefault(norm(e.getKey()), ColumnAggregation.UNKNOWN) != ColumnAggregation.ADDITIVE) continue;
                    List<BigDecimal> vals = e.getValue();
                    BigDecimal grand = sum(nonNull(vals));
                    sb.append("\nSubtotal of ").append(e.getKey()).append(" by ").append(dim).append(":\n");
                    for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
                        BigDecimal sub = BigDecimal.ZERO;
                        for (int i : g.getValue()) {
                            if (vals.get(i) != null) sub = sub.add(vals.get(i));
                        }
                        sb.append("- ").append(g.getKey()).append(": ").append(fmt(sub, figures));
                        if (grand.signum() > 0 && allNonNegative(vals)) {
                            sb.append(" (").append(pct(sub, grand, figures)).append("% of total)");
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        // First-to-last change for a single time series.
        if (dimensions.size() == 1 && n >= 2 && isDateColumn(data, dimensions.get(0))) {
            String dateCol = dimensions.get(0);
            int first = 0;
            int last = 0;
            for (int i = 1; i < n; i++) {
                String v = str(data.get(i).get(dateCol));
                if (v.compareTo(str(data.get(first).get(dateCol))) < 0) first = i;
                if (v.compareTo(str(data.get(last).get(dateCol))) > 0) last = i;
            }
            if (first != last) {
                sb.append("\nChange from ").append(str(data.get(first).get(dateCol)))
                        .append(" to ").append(str(data.get(last).get(dateCol))).append(":\n");
                for (Map.Entry<String, List<BigDecimal>> e : numeric.entrySet()) {
                    BigDecimal a = e.getValue().get(first);
                    BigDecimal b = e.getValue().get(last);
                    if (a == null || b == null) continue;
                    BigDecimal diff = b.subtract(a);
                    sb.append("- ").append(e.getKey()).append(": ").append(fmt(a, figures))
                            .append(" -> ").append(fmt(b, figures)).append(", change ").append(fmtSigned(diff, figures));
                    if (a.signum() != 0) {
                        sb.append(" (").append(pctSigned(diff, a.abs(), figures)).append("%)");
                    }
                    sb.append("\n");
                }
            }
        }

        // Row shares for additive columns. Listed last: this is the longest
        // section, so the block cap trims it before the summaries above.
        if (n <= MAX_SHARE_ROWS && n > 1) {
            for (Map.Entry<String, List<BigDecimal>> e : numeric.entrySet()) {
                if (shape.kinds.getOrDefault(norm(e.getKey()), ColumnAggregation.UNKNOWN) != ColumnAggregation.ADDITIVE) continue;
                List<BigDecimal> vals = e.getValue();
                BigDecimal total = sum(nonNull(vals));
                if (!allNonNegative(vals) || total.signum() <= 0) continue;
                // Rank by value (largest first) with running top-N sums, unless
                // the rows are a time series whose order is the point.
                boolean ranked = dimensions.stream().noneMatch(d -> isDateColumn(data, d));
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < n; i++) if (vals.get(i) != null) order.add(i);
                if (ranked) order.sort((a, b) -> vals.get(b).compareTo(vals.get(a)));
                sb.append("\nShare of ").append(e.getKey()).append(" total by row")
                        .append(ranked ? ", largest first, with running top-N totals" : "").append(":\n");
                BigDecimal running = BigDecimal.ZERO;
                int rank = 0;
                for (int i : order) {
                    sb.append("- ").append(labels.get(i).isEmpty() ? "row " + (i + 1) : labels.get(i))
                            .append(": ").append(pct(vals.get(i), total, figures)).append("%");
                    if (ranked) {
                        running = running.add(vals.get(i));
                        rank++;
                        if (rank > 1 && rank < order.size()) {
                            sb.append(" | top ").append(rank).append(": ").append(fmt(running, figures))
                                    .append(" = ").append(pct(running, total, figures)).append("%");
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        String block = sb.toString();
        if (block.length() > MAX_BLOCK_CHARS) {
            block = block.substring(0, MAX_BLOCK_CHARS) + "\n... (truncated)\n";
        }
        return new Summary(block, figures);
    }

    private static String describe(ColumnAggregation kind) {
        return switch (kind) {
            case ADDITIVE -> "additive";
            case DISTINCT -> "distinct count: rows overlap, never add them up";
            case NON_ADDITIVE -> "ratio/average/running value: never add them up";
            case UNKNOWN -> "stored value: additivity unknown, no total given";
        };
    }

    // ------------------------------------------------------------------
    // SQL shape
    // ------------------------------------------------------------------

    static final class SqlShape {
        final Map<String, ColumnAggregation> kinds = new HashMap<>();
        final Set<String> groupBy = new HashSet<>();
    }

    static SqlShape analyzeSql(String sql) {
        SqlShape shape = new SqlShape();
        if (sql == null || sql.isBlank()) return shape;
        try {
            Statement st = CCJSqlParserUtil.parse(stripTrailingSemicolon(sql));
            if (!(st instanceof Select select)) return shape;
            PlainSelect ps = outerPlainSelect(select.getSelectBody());
            if (ps == null) return shape;
            Map<String, Expression> aliases = new HashMap<>();
            for (SelectItem item : ps.getSelectItems()) {
                if (!(item instanceof SelectExpressionItem sei)) continue;
                Expression expr = sei.getExpression();
                String name = sei.getAlias() != null ? sei.getAlias().getName() : outputName(expr);
                if (name == null) continue;
                shape.kinds.put(norm(name), ColumnAggregation.classify(expr));
                aliases.put(norm(name), expr);
            }
            if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
                for (Object o : ps.getGroupBy().getGroupByExpressionList().getExpressions()) {
                    Expression g = (Expression) o;
                    String name = outputName(g);
                    if (name != null) shape.groupBy.add(norm(name));
                    // GROUP BY 1, 2 style ordinals.
                    String text = g.toString().trim();
                    if (text.matches("\\d+")) {
                        int idx = Integer.parseInt(text) - 1;
                        if (idx >= 0 && idx < ps.getSelectItems().size()
                                && ps.getSelectItems().get(idx) instanceof SelectExpressionItem sei) {
                            String n = sei.getAlias() != null ? sei.getAlias().getName() : outputName(sei.getExpression());
                            if (n != null) shape.groupBy.add(norm(n));
                        }
                    }
                }
                // Aliased select items grouped by their expression.
                for (Map.Entry<String, Expression> a : aliases.entrySet()) {
                    for (Object o : ps.getGroupBy().getGroupByExpressionList().getExpressions()) {
                        if (o.toString().equalsIgnoreCase(a.getValue().toString())) {
                            shape.groupBy.add(a.getKey());
                        }
                    }
                }
            }
        } catch (Exception | Error e) {
            log.debug("[ResultFigures] SQL not parsed, every column treated as unknown: {}", e.getMessage());
        }
        return shape;
    }

    private static PlainSelect outerPlainSelect(SelectBody body) {
        if (body instanceof PlainSelect ps) return ps;
        if (body instanceof SetOperationList sol && sol.getSelects() != null && !sol.getSelects().isEmpty()) {
            // UNION of same-shaped selects: the first branch defines the columns.
            return outerPlainSelect(sol.getSelects().get(0));
        }
        return null;
    }

    private static String outputName(Expression expr) {
        if (expr instanceof Column c) return c.getColumnName();
        return expr == null ? null : expr.toString();
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private static String norm(String name) {
        String s = name.trim();
        if (s.length() >= 2 && (s.startsWith("`") || s.startsWith("\"") || s.startsWith("["))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Column helpers
    // ------------------------------------------------------------------

    /** Values as numbers, or null when the column is not numeric (or is a date). */
    private static List<BigDecimal> numericColumn(List<Map<String, Object>> data, String header) {
        List<BigDecimal> out = new ArrayList<>(data.size());
        int present = 0;
        for (Map<String, Object> row : data) {
            Object v = row.get(header);
            if (v == null || str(v).isEmpty() || "null".equalsIgnoreCase(str(v))) {
                out.add(null);
                continue;
            }
            BigDecimal d = toNumber(v);
            if (d == null) return null;
            if (DATE_LIKE.matcher(str(v)).matches()) return null;
            out.add(d);
            present++;
        }
        return present == 0 ? null : out;
    }

    private static BigDecimal toNumber(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number num) {
            double d = num.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return new BigDecimal(num.toString());
        }
        String s = str(v);
        if (!NUMERIC.matcher(s).matches()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isDateColumn(List<Map<String, Object>> data, String header) {
        int checked = 0;
        for (Map<String, Object> row : data) {
            Object v = row.get(header);
            if (v == null) continue;
            if (!DATE_LIKE.matcher(str(v)).matches()) return false;
            if (++checked >= 20) break;
        }
        return checked > 0;
    }

    private static List<String> rowLabels(List<Map<String, Object>> data, List<String> dimensions) {
        List<String> labels = new ArrayList<>(data.size());
        for (Map<String, Object> row : data) {
            List<String> parts = new ArrayList<>();
            for (String d : dimensions) {
                String v = str(row.get(d));
                if (!v.isEmpty()) parts.add(v.length() > 60 ? v.substring(0, 60) + "..." : v);
            }
            labels.add(String.join(" / ", parts));
        }
        return labels;
    }

    private static Map<String, List<Integer>> groupIndexes(List<Map<String, Object>> data, String dim) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < data.size(); i++) {
            String key = str(data.get(i).get(dim));
            groups.computeIfAbsent(key.isEmpty() ? "(empty)" : key, k -> new ArrayList<>()).add(i);
            if (groups.size() > MAX_GROUP_VALUES) break;
        }
        return groups;
    }

    private static String label(List<String> labels, int idx) {
        String l = labels.get(idx);
        return l.isEmpty() ? "" : " (" + l + ")";
    }

    private static int argExtreme(List<BigDecimal> vals, boolean max) {
        int best = -1;
        for (int i = 0; i < vals.size(); i++) {
            BigDecimal v = vals.get(i);
            if (v == null) continue;
            if (best < 0 || (max ? v.compareTo(vals.get(best)) > 0 : v.compareTo(vals.get(best)) < 0)) best = i;
        }
        return best;
    }

    private static List<BigDecimal> nonNull(List<BigDecimal> vals) {
        List<BigDecimal> out = new ArrayList<>(vals.size());
        for (BigDecimal v : vals) if (v != null) out.add(v);
        return out;
    }

    private static boolean allNonNegative(List<BigDecimal> vals) {
        for (BigDecimal v : vals) if (v != null && v.signum() < 0) return false;
        return true;
    }

    private static BigDecimal sum(Collection<BigDecimal> vals) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal v : vals) s = s.add(v);
        return s;
    }

    private static BigDecimal avg(List<BigDecimal> vals) {
        return sum(vals).divide(BigDecimal.valueOf(vals.size()), 10, RoundingMode.HALF_UP);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static String fmt(BigDecimal v, List<BigDecimal> figures) {
        BigDecimal r = v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        figures.add(r);
        return grouped().format(r);
    }

    private static String fmtSigned(BigDecimal v, List<BigDecimal> figures) {
        return (v.signum() > 0 ? "+" : "") + fmt(v, figures);
    }

    private static String pct(BigDecimal part, BigDecimal total, List<BigDecimal> figures) {
        BigDecimal p = part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
        figures.add(p);
        return p.toPlainString();
    }

    private static String pctSigned(BigDecimal diff, BigDecimal base, List<BigDecimal> figures) {
        String p = pct(diff, base, figures);
        return diff.signum() > 0 ? "+" + p : p;
    }

    private static DecimalFormat grouped() {
        DecimalFormat df = new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.ROOT));
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df;
    }

    // ------------------------------------------------------------------
    // Figure check
    // ------------------------------------------------------------------

    // East Asian large-number units (Korean / CJK), written as escapes to keep
    // the source ASCII: 100,000,000 / 10,000 / 1,000.
    private static final String UNIT_1E8 = "(?:\\uC5B5|\\u5104|\\u4EBF)";
    private static final String UNIT_1E4 = "(?:\\uB9CC|\\u4E07|\\u842C)";
    private static final String UNIT_1E3 = "(?:\\uCC9C|\\u5343)";
    // Calendar / ordinal suffixes (year, month, day, hour, minute, week,
    // quarter, rank, "-th") so dates and ranks are not read as figures.
    private static final String DATE_SUFFIX =
            "(?:\\uB144|\\u5E74|\\uC6D4|\\u6708|\\uC77C|\\u65E5|\\uC2DC|\\u6642|\\uBD84|\\u5206"
            + "|\\uC8FC\\uCC28|\\uBD84\\uAE30|\\uC704|\\u4F4D|\\uBC88\\uC9F8)";

    // Number with optional thousands separators and a unit suffix.
    private static final Pattern FIGURE = Pattern.compile(
            "(?<![\\w.,])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(\\s*(?:%p|%|"
                    + UNIT_1E8 + "|" + UNIT_1E4 + "|" + UNIT_1E3 + "|[KMB](?![A-Za-z])))?");
    private static final Pattern DATES = Pattern.compile(
            "\\d{4}[-./]\\d{1,2}(?:[-./]\\d{1,2})?|\\d{1,2}:\\d{2}(?::\\d{2})?|\\d{1,4}\\s*" + DATE_SUFFIX);
    private static final Pattern CODE = Pattern.compile("```[\\s\\S]*?```|`[^`]*`");

    /**
     * Figures in {@code answer} that are neither a result value, a
     * precomputed figure, nor a simple ratio/difference of those.
     */
    public static List<String> findUnverifiedFigures(String answer, List<Map<String, Object>> rows,
                                                     Summary summary, String question, String sql) {
        // Blank answers and model-call error messages are not analysis text.
        if (answer == null || answer.isBlank() || answer.stripLeading().startsWith("Error:")) return List.of();

        List<BigDecimal> base = new ArrayList<>();
        if (rows != null) {
            List<Map<String, Object>> data = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
            for (Map<String, Object> row : data) {
                for (Object v : row.values()) {
                    BigDecimal d = v == null ? null : toNumber(v);
                    if (d != null) base.add(d);
                }
            }
            base.add(BigDecimal.valueOf(data.size()));
        }
        List<BigDecimal> derived = new ArrayList<>(summary == null ? List.of() : summary.figures());

        // Pairwise ratios / differences of a bounded value set.
        LinkedHashSet<BigDecimal> pool = new LinkedHashSet<>(derived);
        for (BigDecimal b : base) {
            if (pool.size() >= MAX_PAIRWISE_VALUES) break;
            pool.add(b);
        }
        List<BigDecimal> poolList = new ArrayList<>(pool);
        List<Double> allowed = new ArrayList<>(base.size() + derived.size() + poolList.size() * poolList.size() * 3);
        for (BigDecimal b : base) allowed.add(b.doubleValue());
        for (BigDecimal b : derived) allowed.add(b.doubleValue());
        for (BigDecimal a : poolList) {
            double av = a.doubleValue();
            for (BigDecimal b : poolList) {
                double bv = b.doubleValue();
                if (a == b) continue;
                allowed.add(av - bv);
                if (bv != 0) {
                    allowed.add(av / bv);
                    allowed.add(av / bv * 100);
                    allowed.add((av - bv) / Math.abs(bv) * 100);
                }
            }
        }

        Set<String> context = new HashSet<>();
        collectRawNumbers(question, context);
        collectRawNumbers(sql, context);

        String text = CODE.matcher(answer).replaceAll(" ");
        text = DATES.matcher(text).replaceAll(" ");

        List<Figure> figures = parseFigures(text);
        LinkedHashSet<String> unverified = new LinkedHashSet<>();
        for (Figure f : figures) {
            if (f.skip(context)) continue;
            if (!matches(f, allowed)) unverified.add(f.raw.trim());
            if (unverified.size() >= MAX_UNVERIFIED) break;
        }
        return new ArrayList<>(unverified);
    }

    private static void collectRawNumbers(String s, Set<String> out) {
        if (s == null) return;
        Matcher m = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(s);
        while (m.find()) out.add(m.group().replace(",", ""));
    }

    static final class Figure {
        final String raw;
        final String digits;
        final double value;
        final double tolerance;
        final boolean percent;
        final boolean hasUnit;

        Figure(String raw, String digits, double value, double tolerance, boolean percent, boolean hasUnit) {
            this.raw = raw;
            this.digits = digits;
            this.value = value;
            this.tolerance = tolerance;
            this.percent = percent;
            this.hasUnit = hasUnit;
        }

        boolean skip(Set<String> context) {
            if (!hasUnit && !percent) {
                // Small counts ("3 regions", "top 5") and years.
                if (value == Math.rint(value) && Math.abs(value) <= 10) return true;
                if (value == Math.rint(value) && value >= 1900 && value <= 2100) return true;
                if (context.contains(digits)) return true;
            }
            return false;
        }
    }

    static List<Figure> parseFigures(String text) {
        List<Figure> out = new ArrayList<>();
        Matcher m = FIGURE.matcher(text);
        Figure pending = null;
        int pendingEnd = -1;
        while (m.find()) {
            String num = m.group(1).replace(",", "");
            String unit = m.group(2) == null ? "" : m.group(2).trim();
            int decimals = num.contains(".") ? num.length() - num.indexOf('.') - 1 : 0;
            double scale = unitScale(unit);
            double value;
            try {
                value = Double.parseDouble(num) * scale;
            } catch (NumberFormatException e) {
                continue;
            }
            double tol = 0.5 * Math.pow(10, -decimals) * scale;
            boolean percent = unit.startsWith("%");
            Figure f = new Figure(m.group(), num, value, tol, percent, !unit.isEmpty());

            // Compound amounts written as "<n> x 10^8 <m> x 10^4" or
            // "<n> x 10^4 <m>" in East Asian unit notation.
            if (pending != null && !percent && m.start() - pendingEnd <= 2
                    && text.substring(pendingEnd, m.start()).isBlank()
                    && scale < unitScale(pending.raw)) {
                double combined = pending.value + value;
                pending = new Figure(pending.raw + " " + f.raw, pending.digits, combined,
                        Math.min(pending.tolerance, tol), false, true);
                pendingEnd = m.end();
                continue;
            }
            if (pending != null) out.add(pending);
            if (scale >= 1e4 && !percent) {
                pending = f;
                pendingEnd = m.end();
            } else {
                pending = null;
                out.add(f);
            }
        }
        if (pending != null) out.add(pending);
        return out;
    }

    private static final Pattern ENDS_1E8 = Pattern.compile(UNIT_1E8 + "$");
    private static final Pattern ENDS_1E4 = Pattern.compile(UNIT_1E4 + "$");
    private static final Pattern ENDS_1E3 = Pattern.compile(UNIT_1E3 + "$");

    /** Multiplier for a figure's unit suffix (also accepts the whole figure text). */
    private static double unitScale(String raw) {
        String r = raw.trim();
        if (ENDS_1E8.matcher(r).find()) return 1e8;
        if (ENDS_1E4.matcher(r).find()) return 1e4;
        if (ENDS_1E3.matcher(r).find()) return 1e3;
        if (r.endsWith("K")) return 1e3;
        if (r.endsWith("M")) return 1e6;
        if (r.endsWith("B")) return 1e9;
        return 1;
    }

    private static boolean matches(Figure f, List<Double> allowed) {
        double tol = Math.max(f.tolerance, Math.abs(f.value) * 1e-9);
        for (double a : allowed) {
            if (Math.abs(Math.abs(a) - Math.abs(f.value)) <= tol) return true;
            // A ratio column stored as 0.664 written as 66.4%.
            if (f.percent && Math.abs(Math.abs(a * 100) - Math.abs(f.value)) <= tol) return true;
        }
        return false;
    }
}
