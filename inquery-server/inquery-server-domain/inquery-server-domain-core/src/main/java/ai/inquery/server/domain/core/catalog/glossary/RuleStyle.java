package ai.inquery.server.domain.core.catalog.glossary;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks whether a data rule is written as an instruction.
 *
 * <p>Models apply "Never filter X with LIKE; always use ..." reliably, but
 * tend to ignore the same knowledge written as a fact ("X consists of A, B
 * and C"): a narrow filter returns a plausible number with no error, so
 * nothing prompts a correction. Rules are still saved either way; this only
 * produces a warning with an example rewrite.
 */
public final class RuleStyle {

    private RuleStyle() {
    }

    // Directive words in the supported UI languages (English, Korean, Japanese, Turkish).
    private static final List<Pattern> DIRECTIVES = List.of(
            Pattern.compile("\\b(must|never|always|only|do not|don't|dont|should|avoid|exclude|include|use|"
                    + "filter|count|treat|prefer|ignore|require|ensure|make sure|instead of)\\b"),
            Pattern.compile("(\\ubc18\\ub4dc\\uc2dc|\\ud56d\\uc0c1|\\uc808\\ub300|\\ud558\\uc9c0 \\ub9c8|"
                    + "\\ub9d0 \\uac83|\\uae08\\uc9c0|\\uc81c\\uc678|\\ud3ec\\ud568|\\uc0ac\\uc6a9|\\ub9cc \\uc0ac\\uc6a9|"
                    + "\\ud574\\uc57c|\\ud558\\ub77c|\\ud560 \\uac83)"),
            Pattern.compile("(\\u5fc5\\u305a|\\u5e38\\u306b|\\u7d76\\u5bfe|\\u3057\\u306a\\u3044|\\u7981\\u6b62|"
                    + "\\u9664\\u5916|\\u4f7f\\u7528|\\u3053\\u3068)"),
            Pattern.compile("\\b(asla|her zaman|mutlaka|kullan|hari\\u00e7|dahil)"));

    public static final String WARNING = "This rule reads like a description. Rules written as instructions are "
            + "followed far more reliably, e.g. \"Never filter X with LIKE; always combine A, B and C with OR\" "
            + "instead of \"X consists of A, B and C\".";

    /** True when the rule contains no directive word. */
    public static boolean looksDescriptive(String rule) {
        if (rule == null || rule.isBlank()) return false;
        String text = rule.toLowerCase(Locale.ROOT);
        for (Pattern p : DIRECTIVES) {
            if (p.matcher(text).find()) return false;
        }
        return true;
    }
}
