package ai.inquery.server.domain.core.catalog;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facts about the tables a failed SQL references, for the SQL fixer.
 *
 * <p>A fixer that is only told "column not found" fills the gap with a
 * plausible guess. Every table the SQL names gets its real column list, or
 * an explicit "does not exist" with the closest existing table names, so
 * nothing is left to invent. Broken SQL often does not parse, so table
 * names fall back to a FROM/JOIN scan.
 */
public final class FixFacts {

    private FixFacts() {
    }

    private static final Pattern FROM_JOIN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+((?:[`\"\\[]?[\\w$-]+[`\"\\]]?\\.){0,2}[`\"\\[]?[\\w$-]+[`\"\\]]?)");

    /** Table names as written in the SQL (CTE names excluded when the SQL parses). */
    public static List<String> referencedTables(String sql) {
        Set<String> out = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) return new ArrayList<>();
        try {
            Statement st = CCJSqlParserUtil.parse(sql.trim().replaceAll(";+\\s*$", ""));
            out.addAll(new TablesNamesFinder().getTableList(st));
        } catch (Exception | Error e) {
            Set<String> ctes = new LinkedHashSet<>();
            Matcher cte = Pattern.compile("(?i)(?:with|,)\\s*([\\w$]+)\\s+as\\s*\\(").matcher(sql);
            while (cte.find()) ctes.add(cte.group(1).toLowerCase(Locale.ROOT));
            Matcher m = FROM_JOIN.matcher(sql);
            while (m.find()) {
                String name = m.group(1);
                if (!ctes.contains(stripQuotes(name).toLowerCase(Locale.ROOT))) out.add(name);
            }
        }
        out.removeIf(t -> t.startsWith("(") || t.equalsIgnoreCase("select") || t.equalsIgnoreCase("unnest"));
        return new ArrayList<>(out);
    }

    /** Up to {@code k} candidate names closest to {@code missing} (by table name part). */
    public static List<String> nearest(String missing, List<String> candidates, int k) {
        String target = lastPart(missing);
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((String c) -> -similarity(target, lastPart(c))).thenComparing(c -> c));
        List<String> out = new ArrayList<>();
        for (String c : sorted) {
            if (similarity(target, lastPart(c)) < 0.34) break;
            out.add(c);
            if (out.size() >= k) break;
        }
        return out;
    }

    /**
     * @param columns  table as written in the SQL -> column name -> type; a null value means the table was not found
     * @param similar  table as written -> closest existing table names (for tables not found)
     */
    public static String format(Map<String, Map<String, String>> columns, Map<String, List<String>> similar) {
        return format(columns, similar, null);
    }

    // Missing-column wording across databases (PostgreSQL, BigQuery, MySQL, Snowflake/Oracle, SQL Server, ...).
    private static final List<Pattern> MISSING_COLUMN = List.of(
            Pattern.compile("(?i)column \"?([\\w.$]+)\"? does not exist"),
            Pattern.compile("(?i)unrecognized name:\\s*`?([\\w$]+)`?"),
            Pattern.compile("(?i)unknown column '([\\w.$]+)'"),
            Pattern.compile("(?i)invalid identifier '?\"?([\\w.$]+)\"?'?"),
            Pattern.compile("(?i)invalid column name '([\\w.$]+)'"),
            Pattern.compile("(?i)no such column:\\s*([\\w.$]+)"),
            Pattern.compile("(?i)cannot resolve '`?([\\w.$]+)`?'"));

    /** The column a "column not found" error names, without table qualifier; null when none. */
    public static String missingColumn(String error) {
        if (error == null) return null;
        for (Pattern p : MISSING_COLUMN) {
            Matcher m = p.matcher(error);
            if (m.find()) {
                String c = m.group(1);
                int dot = c.lastIndexOf('.');
                return dot >= 0 ? c.substring(dot + 1) : c;
            }
        }
        return null;
    }

    /**
     * @param error the database error; when it names a missing column that no
     *              listed table has, that is stated explicitly with close names
     */
    public static String format(Map<String, Map<String, String>> columns, Map<String, List<String>> similar,
                                String error) {
        if (columns == null || columns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Facts about the tables in the failed SQL (from the live database):\n");
        for (Map.Entry<String, Map<String, String>> e : columns.entrySet()) {
            Map<String, String> cols = e.getValue();
            if (cols == null || cols.isEmpty()) {
                sb.append("- ").append(e.getKey()).append(": DOES NOT EXIST.");
                List<String> alt = similar == null ? null : similar.get(e.getKey());
                if (alt != null && !alt.isEmpty()) {
                    sb.append(" Closest existing tables: ").append(String.join(", ", alt)).append('.');
                }
                sb.append('\n');
            } else {
                sb.append("- ").append(e.getKey()).append(" columns: ");
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, String> c : cols.entrySet()) {
                    parts.add(c.getValue() == null || c.getValue().isBlank() ? c.getKey() : c.getKey() + " " + c.getValue());
                }
                sb.append(String.join(", ", parts)).append('\n');
            }
        }
        String missing = missingColumn(error);
        if (missing != null) {
            List<String> all = new ArrayList<>();
            boolean exists = false;
            for (Map<String, String> cols : columns.values()) {
                if (cols == null) continue;
                for (String c : cols.keySet()) {
                    all.add(c);
                    if (c.equalsIgnoreCase(missing)) exists = true;
                }
            }
            if (!exists && !all.isEmpty()) {
                sb.append("`").append(missing).append("` is not a column of any table above; do not use it.");
                List<String> close = nearest(missing, all, 3);
                if (!close.isEmpty()) sb.append(" Closest listed columns: ").append(String.join(", ", close)).append('.');
                sb.append('\n');
            }
        }
        sb.append("Use only tables and columns listed above. Never invent a table or column name; "
                + "if a needed column is not listed, choose the closest listed one or an existing table that has it.\n");
        return sb.toString();
    }

    static double similarity(String a, String b) {
        a = a.toLowerCase(Locale.ROOT);
        b = b.toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        int d = levenshtein(a, b);
        double edit = 1.0 - (double) d / Math.max(a.length(), b.length());
        // Shared underscore-separated tokens (orders vs order_items vs orders_daily).
        Set<String> ta = new LinkedHashSet<>(List.of(a.split("_")));
        Set<String> tb = new LinkedHashSet<>(List.of(b.split("_")));
        long shared = ta.stream().filter(t -> tb.stream().anyMatch(u -> u.startsWith(t) || t.startsWith(u))).count();
        double token = (double) shared / Math.max(ta.size(), tb.size());
        return Math.max(edit, token);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    static String lastPart(String name) {
        String s = stripQuotes(name);
        int dot = s.lastIndexOf('.');
        return stripQuotes(dot >= 0 ? s.substring(dot + 1) : s);
    }

    private static String stripQuotes(String s) {
        return s.replaceAll("[`\"\\[\\]]", "");
    }

    /** Insertion-ordered map helper for callers. */
    public static <V> Map<String, V> orderedMap() {
        return new LinkedHashMap<>();
    }
}
