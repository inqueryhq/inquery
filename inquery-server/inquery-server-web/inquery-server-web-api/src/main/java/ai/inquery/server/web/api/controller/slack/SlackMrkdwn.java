package ai.inquery.server.web.api.controller.slack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the standard markdown the agents write into Slack mrkdwn right before
 * a message is posted. Slack shows headings, "**bold**", horizontal rules, table
 * pipes and the language tag of a code fence as literal text.
 */
public final class SlackMrkdwn {

    private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");

    private SlackMrkdwn() {}

    public static String convert(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inCode = false;
        List<String> table = new ArrayList<>();

        for (String line : lines) {
            if (FENCE.matcher(line).matches()) {
                flushTable(table, out);
                // Slack prints the language tag ("```sql") as the first code line.
                out.add("```");
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                out.add(line);
                continue;
            }
            if (TABLE_ROW.matcher(line).matches()) {
                table.add(line);
                continue;
            }
            flushTable(table, out);

            if (RULE.matcher(line).matches()) {
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                out.add("*" + stripEmphasis(h.group(1)) + "*");
                continue;
            }
            Matcher b = BULLET.matcher(line);
            if (b.matches()) {
                out.add(b.group(1) + "• " + inline(b.group(2)));
                continue;
            }
            out.add(inline(line));
        }
        flushTable(table, out);
        return String.join("\n", out).replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Inline rules applied outside inline code spans. */
    private static String inline(String text) {
        StringBuilder sb = new StringBuilder();
        Matcher code = INLINE_CODE.matcher(text);
        int last = 0;
        while (code.find()) {
            sb.append(inlinePlain(text.substring(last, code.start()))).append(code.group());
            last = code.end();
        }
        sb.append(inlinePlain(text.substring(last)));
        return sb.toString();
    }

    private static String inlinePlain(String s) {
        // Italic first (single *), then bold (** / __) becomes Slack's single *.
        s = ITALIC.matcher(s).replaceAll("_$1_");
        s = BOLD.matcher(s).replaceAll("*$2*");
        s = STRIKE.matcher(s).replaceAll("~$1~");
        s = LINK.matcher(s).replaceAll("<$2|$1>");
        return s;
    }

    private static String stripEmphasis(String s) {
        return s.replaceAll("\\*\\*|__", "").replace("*", "");
    }

    /** Slack has no tables: render the rows as an aligned monospace block. */
    private static void flushTable(List<String> table, List<String> out) {
        if (table.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        for (String row : table) {
            if (TABLE_SEPARATOR.matcher(row).matches()) continue;
            String trimmed = row.trim();
            trimmed = trimmed.substring(1, trimmed.length() - 1);
            String[] cells = trimmed.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) cells[i] = stripEmphasis(cells[i].trim()).replace("`", "");
            rows.add(cells);
        }
        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(0);
        int[] width = new int[cols];
        for (String[] r : rows) for (int i = 0; i < r.length; i++) width[i] = Math.max(width[i], r[i].length());
        out.add("```");
        for (String[] r : rows) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                String cell = i < r.length ? r[i] : "";
                sb.append(cell).append(" ".repeat(width[i] - cell.length()));
                if (i < cols - 1) sb.append("  ");
            }
            out.add(sb.toString().stripTrailing());
        }
        out.add("```");
        table.clear();
    }
}
