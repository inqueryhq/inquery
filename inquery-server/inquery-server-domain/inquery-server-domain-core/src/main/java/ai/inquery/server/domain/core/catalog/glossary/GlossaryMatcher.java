package ai.inquery.server.domain.core.catalog.glossary;

import ai.inquery.server.domain.api.model.CatalogMetricDTO;
import ai.inquery.server.domain.api.model.CatalogTermDTO;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Matches a user phrase against glossary terms and metric definitions, and
 * formats the result for the assistant.
 *
 * <p>Exact matches (canonical value, metric name or any alias, after
 * normalisation) always win; partial matches are only returned when nothing
 * matches exactly, so a longer alias never drags in a shorter one and vice
 * versa. Matching is deterministic and only runs when the assistant calls the
 * lookup tool; the server never rewrites the user's question.
 */
public final class GlossaryMatcher {

    private static final Pattern IGNORED = Pattern.compile("[\\s\\-_.'\"`]+");
    private static final int MIN_PARTIAL_LENGTH = 2;

    private GlossaryMatcher() {
    }

    /** Lower-cased, width-normalised, without spaces, hyphens, underscores, dots or quotes. */
    public static String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return IGNORED.matcher(s).replaceAll("");
    }

    /** Matching entries and whether they matched exactly. */
    public record Result<T>(List<T> matches, boolean exact) {
        public boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    public static Result<CatalogTermDTO> matchTerms(List<CatalogTermDTO> terms, String phrase) {
        return match(terms, phrase, t -> keys(t.getCanonicalValue(), t.getAliases()));
    }

    public static Result<CatalogMetricDTO> matchMetrics(List<CatalogMetricDTO> metrics, String phrase) {
        return match(metrics, phrase, m -> keys(m.getName(), m.getAliases()));
    }

    private static List<String> keys(String primary, List<String> aliases) {
        List<String> out = new ArrayList<>();
        out.add(normalize(primary));
        if (aliases != null) for (String a : aliases) out.add(normalize(a));
        out.removeIf(String::isEmpty);
        return out;
    }

    private static <T> Result<T> match(List<T> entries, String phrase, Function<T, List<String>> keysOf) {
        String q = normalize(phrase);
        if (q.isEmpty() || entries == null) return new Result<>(List.of(), false);
        List<T> exact = new ArrayList<>();
        for (T e : entries) {
            if (keysOf.apply(e).contains(q)) exact.add(e);
        }
        if (!exact.isEmpty()) return new Result<>(exact, true);
        List<T> partial = new ArrayList<>();
        for (T e : entries) {
            for (String k : keysOf.apply(e)) {
                boolean longEnough = Math.min(k.length(), q.length()) >= MIN_PARTIAL_LENGTH;
                if (longEnough && (k.contains(q) || q.contains(k))) {
                    partial.add(e);
                    break;
                }
            }
        }
        return new Result<>(partial, false);
    }

    // ------------------------------------------------------------------
    // Tool output
    // ------------------------------------------------------------------

    public static String formatTermLookup(String phrase, Result<CatalogTermDTO> result) {
        StringBuilder sb = new StringBuilder();
        if (result.isEmpty()) {
            sb.append("NO_TERM_FOUND for \"").append(phrase).append("\". The glossary has no entry for it. ")
                    .append("Do not guess its meaning: search the catalog or probe column values, ")
                    .append("or ask the user what it refers to.");
            return sb.toString();
        }
        sb.append(result.exact() ? "TERM_MATCH" : "TERM_PARTIAL_MATCH")
                .append(" for \"").append(phrase).append("\":\n");
        for (CatalogTermDTO t : result.matches()) {
            sb.append("- \"").append(t.getCanonicalValue()).append("\"");
            if (notBlank(t.getColumnRef())) sb.append(" in column ").append(t.getColumnRef());
            if (t.getAliases() != null && !t.getAliases().isEmpty()) {
                sb.append(" (aliases: ").append(String.join(", ", t.getAliases())).append(")");
            }
            if (notBlank(t.getNote())) sb.append(". Note: ").append(t.getNote().trim());
            sb.append("\n");
        }
        sb.append("Use the canonical value exactly as written (equality, not LIKE) when filtering.");
        if (!result.exact()) {
            sb.append("\nThese are partial matches only: use one only if it clearly is what the user meant.");
        }
        if (result.matches().size() > 1) {
            sb.append("\nAMBIGUOUS: \"").append(phrase).append("\" maps to ").append(result.matches().size())
                    .append(" values. If the question already says which one (year, season, version, ...), use it. ")
                    .append("If one query can show all of them side by side, show them separately instead of asking. ")
                    .append("Otherwise call request_clarification with these values as the options.");
        }
        return sb.toString();
    }

    public static String formatMetricLookup(String phrase, Result<CatalogMetricDTO> result) {
        if (result.isEmpty()) {
            return "NO_OFFICIAL_METRIC for \"" + phrase + "\". There is no registered definition; "
                    + "derive it from the catalog and say which table and formula you used.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(result.exact() ? "OFFICIAL_METRIC" : "OFFICIAL_METRIC_PARTIAL_MATCH")
                .append(" for \"").append(phrase).append("\":\n");
        for (CatalogMetricDTO m : result.matches()) {
            sb.append(describeMetric(m)).append("\n");
        }
        sb.append("Compute the metric exactly this way: pass the table, expression and filters to queryData ")
                .append("in conversation_context. Do not substitute another table or formula.");
        if (!result.exact()) {
            sb.append("\nThese are partial matches only: use one only if it clearly is what the user meant.");
        }
        return sb.toString();
    }

    /** Multi-line description of one metric definition. */
    public static String describeMetric(CatalogMetricDTO m) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(m.getName());
        if (m.getAliases() != null && !m.getAliases().isEmpty()) {
            sb.append(" (aliases: ").append(String.join(", ", m.getAliases())).append(")");
        }
        if (notBlank(m.getDescription())) sb.append(": ").append(m.getDescription().trim());
        sb.append("\n  Table: ").append(m.getTableRef());
        sb.append("\n  Expression: ").append(m.getExpression());
        if (notBlank(m.getFilters())) sb.append("\n  Filters: ").append(m.getFilters());
        if (notBlank(m.getGrain())) sb.append("\n  Grain: ").append(m.getGrain());
        if (notBlank(m.getAggregation())) sb.append("\n  Aggregation: ").append(m.getAggregation());
        if (notBlank(m.getCaveats())) sb.append("\n  Caveats: ").append(m.getCaveats().trim());
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
