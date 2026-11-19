package ai.inquery.server.domain.core.catalog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notes about how current the data behind a query result is.
 *
 * <p>Two silent traps: the last period of a time series is still in progress
 * (this month so far compared with full months), and the requested range ends
 * after the last day the table has data for. Both produce a plausible number
 * that is not comparable with the others. Given each table's latest date,
 * this class states those facts so the answer can say so.
 */
public final class FreshnessNotes {

    private FreshnessNotes() {
    }

    /** Latest date present in {@code column} of {@code table}. */
    public record DataThrough(String table, String column, LocalDate latest) {
    }

    enum Grain {
        DAY(1, "day"), WEEK(7, "week"), MONTH(0, "month"), QUARTER(0, "quarter"), YEAR(0, "year");

        final int days;
        final String label;

        Grain(int days, String label) {
            this.days = days;
            this.label = label;
        }

        LocalDate end(LocalDate start) {
            return switch (this) {
                case DAY -> start;
                case WEEK -> start.plusDays(6);
                case MONTH -> start.plusMonths(1).minusDays(1);
                case QUARTER -> start.plusMonths(3).minusDays(1);
                case YEAR -> start.plusYears(1).minusDays(1);
            };
        }
    }

    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?(?:[ T].*)?$");

    /**
     * @param sql     the executed SQL
     * @param through latest date per table/date column the SQL uses
     * @param headers result column names
     * @param rows    result rows (string cells)
     */
    public static List<String> notes(String sql, List<DataThrough> through, List<String> headers,
                                     List<List<String>> rows) {
        return notes(sql, through, headers, rows, LocalDate.now());
    }

    /**
     * @param today used when the date column holds period starts (a monthly
     *              table's latest value is the 1st of its latest month, which
     *              says nothing about how many days that month has data for)
     */
    public static List<String> notes(String sql, List<DataThrough> through, List<String> headers,
                                     List<List<String>> rows, LocalDate today) {
        List<String> out = new ArrayList<>();
        if (through == null || through.isEmpty()) return out;

        // The stalest table bounds what the result can contain.
        DataThrough stalest = through.get(0);
        for (DataThrough t : through) {
            if (t.latest() != null && (stalest.latest() == null || t.latest().isBefore(stalest.latest()))) stalest = t;
        }
        LocalDate latest = stalest.latest();
        if (latest == null) return out;

        // 1) Last period of a time series still in progress.
        String partial = partialLastPeriod(headers, rows, latest, today);
        if (partial != null) out.add(partial);

        // 2) Requested range ends after the data does.
        LocalDate upper = upperBound(sql, stalest.column());
        if (upper != null && upper.isAfter(latest)) {
            out.add("Data in " + stalest.table() + " runs through " + latest + ", but the query asks for dates up to "
                    + upper + "; there are no rows after " + latest + ".");
        }
        return out;
    }

    static String partialLastPeriod(List<String> headers, List<List<String>> rows, LocalDate latest, LocalDate today) {
        if (headers == null || rows == null || rows.isEmpty()) return null;
        for (int c = 0; c < headers.size(); c++) {
            TreeSet<LocalDate> values = new TreeSet<>();
            boolean monthText = true;
            boolean allDates = true;
            for (List<String> row : rows) {
                String v = c < row.size() ? row.get(c) : null;
                if (v == null || v.isBlank()) continue;
                Matcher m = DATE.matcher(v.trim());
                if (!m.matches()) { allDates = false; break; }
                if (m.group(3) != null) monthText = false;
                try {
                    values.add(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            m.group(3) == null ? 1 : Integer.parseInt(m.group(3))));
                } catch (Exception e) {
                    allDates = false;
                    break;
                }
            }
            if (!allDates || values.size() < 2) continue;
            Grain grain = monthText ? Grain.MONTH : grainOf(values);
            if (grain == Grain.DAY) continue;
            LocalDate last = values.last();
            LocalDate end = grain.end(last);
            if (latest.isEqual(last)) {
                // The column holds period starts: judge by today instead.
                if (today == null || today.isAfter(end) || today.isBefore(last)) continue;
                return "The latest " + grain.label + " in the result (" + label(last, grain) + ") is still in progress "
                        + "(today is " + today + "), so it is partial and not comparable with full " + grain.label + "s.";
            }
            if (!latest.isBefore(end) || latest.isBefore(last)) continue;
            long have = ChronoUnit.DAYS.between(last, latest) + 1;
            long total = ChronoUnit.DAYS.between(last, end) + 1;
            return "The latest " + grain.label + " in the result (" + label(last, grain) + ") only has data through "
                    + latest + " (" + have + " of " + total + " days), so it is partial and not comparable with full "
                    + grain.label + "s.";
        }
        return null;
    }

    static Grain grainOf(TreeSet<LocalDate> values) {
        boolean firstOfMonth = values.stream().allMatch(d -> d.getDayOfMonth() == 1);
        if (firstOfMonth) {
            boolean jan = values.stream().allMatch(d -> d.getMonthValue() == 1);
            if (jan) return Grain.YEAR;
            boolean quarterStarts = values.stream().allMatch(d -> (d.getMonthValue() - 1) % 3 == 0);
            if (quarterStarts && minGapMonths(values) >= 3) return Grain.QUARTER;
            return Grain.MONTH;
        }
        DayOfWeek dow = values.first().getDayOfWeek();
        boolean sameWeekday = values.stream().allMatch(d -> d.getDayOfWeek() == dow);
        if (sameWeekday) return Grain.WEEK;
        return Grain.DAY;
    }

    private static long minGapMonths(TreeSet<LocalDate> values) {
        long min = Long.MAX_VALUE;
        LocalDate prev = null;
        for (LocalDate d : values) {
            if (prev != null) min = Math.min(min, ChronoUnit.MONTHS.between(prev, d));
            prev = d;
        }
        return min;
    }

    private static String label(LocalDate start, Grain grain) {
        return switch (grain) {
            case MONTH -> start.toString().substring(0, 7);
            case YEAR -> String.valueOf(start.getYear());
            case QUARTER -> start.getYear() + "-Q" + ((start.getMonthValue() - 1) / 3 + 1);
            default -> "starting " + start;
        };
    }

    private static final String LIT = "(?:DATE\\s*|TIMESTAMP\\s*)?'(\\d{4}-\\d{2}-\\d{2})[^']*'";

    /**
     * Upper bound the WHERE clause puts on {@code column}: {@code <= d}, {@code < d}
     * (the day before) or {@code BETWEEN a AND d}. Null when there is none.
     */
    static LocalDate upperBound(String sql, String column) {
        if (sql == null || column == null) return null;
        String col = "(?:\\w+\\.)?[`\"]?" + Pattern.quote(column) + "[`\"]?";
        LocalDate best = null;
        Matcher le = Pattern.compile(col + "\\s*(<=?)\\s*" + LIT, Pattern.CASE_INSENSITIVE).matcher(sql);
        while (le.find()) {
            LocalDate d = parse(le.group(2));
            if (d == null) continue;
            if ("<".equals(le.group(1))) d = d.minusDays(1);
            best = best == null || d.isAfter(best) ? d : best;
        }
        Matcher ge = Pattern.compile(LIT + "\\s*(>=?)\\s*" + col, Pattern.CASE_INSENSITIVE).matcher(sql);
        while (ge.find()) {
            LocalDate d = parse(ge.group(1));
            if (d == null) continue;
            if (">".equals(ge.group(2))) d = d.minusDays(1);
            best = best == null || d.isAfter(best) ? d : best;
        }
        Matcher bt = Pattern.compile(col + "\\s+BETWEEN\\s+" + LIT + "\\s+AND\\s+" + LIT, Pattern.CASE_INSENSITIVE).matcher(sql);
        while (bt.find()) {
            LocalDate d = parse(bt.group(2));
            if (d != null) best = best == null || d.isAfter(best) ? d : best;
        }
        return best;
    }

    private static LocalDate parse(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** True when a column type is a date or timestamp type. */
    public static boolean isDateType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.startsWith("date") || t.startsWith("timestamp") || t.contains("datetime");
    }
}
