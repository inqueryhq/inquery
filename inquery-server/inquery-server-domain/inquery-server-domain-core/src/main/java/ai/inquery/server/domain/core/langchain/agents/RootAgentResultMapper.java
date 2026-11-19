package ai.inquery.server.domain.core.langchain.agents;

import ai.inquery.server.domain.core.query.QueryProcessingResult;
import ai.inquery.server.domain.core.query.QueryType;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.ExecuteResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the state an {@link InqueryRootAgentRunner} leaves behind after
 * {@code run()} onto a {@link QueryProcessingResult}.
 *
 * <p>Both front doors of the root agent share this mapping: the web chat
 * (ChatController) and the Slack bot (SlackChatService). Keeping it in one
 * place is what makes the two surfaces answer the same way — the runner
 * exposes its output through getters, not through a return value, so every
 * caller would otherwise re-derive which getter wins for which branch.
 */
public final class RootAgentResultMapper {

    private RootAgentResultMapper() {
    }

    /**
     * Generates the clickable follow-up suggestions rendered under a result
     * table. Web chat supplies an LLM-backed implementation; surfaces without
     * a place to render buttons pass {@code null}.
     */
    @FunctionalInterface
    public interface FollowUpGenerator {
        List<QueryProcessingResult.SuggestedFollowUp> generate(String userMessage,
                                                              QueryProcessingResult result,
                                                              ExecuteResult executeResult);
    }

    /**
     * @param agentResponse         the string {@code runner.run()} returned
     * @param dateRangePrompt       fallback text when the agent asked for a date range without one
     * @param clarificationPrompt   fallback text when the agent asked to disambiguate without one
     * @param followUpGenerator     nullable; skipped when the surface cannot render follow-ups
     */
    public static QueryProcessingResult map(InqueryRootAgentRunner runner,
                                            String agentResponse,
                                            String userMessage,
                                            String dateRangePrompt,
                                            String clarificationPrompt,
                                            FollowUpGenerator followUpGenerator) {

        QueryProcessingResult result = new QueryProcessingResult();
        result.setOriginalQuery(userMessage);
        result.setQueryType(QueryType.AGENT);

        // Date-picker UX branch — the agent called request_date_range.
        if (runner.isNeedsDateRange()) {
            String prompt = runner.getDateRangePrompt() != null
                    ? runner.getDateRangePrompt()
                    : dateRangePrompt;
            result.setNeedsDateRange(true);
            result.setAiMessage(prompt);
            result.setExplanation(prompt);
            result.setNeedsExecution(false);
            return result;
        }

        // Disambiguation UX branch — the agent called request_clarification.
        if (runner.isNeedsClarification()) {
            String prompt = runner.getClarificationPrompt() != null
                    ? runner.getClarificationPrompt()
                    : clarificationPrompt;
            List<Map<String, String>> opts = new ArrayList<>();
            if (runner.getClarificationOptions() != null) {
                for (String label : runner.getClarificationOptions()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("label", label);
                    m.put("query", label);
                    opts.add(m);
                }
            }
            result.setNeedsDisambiguation(true);
            result.setDisambiguationOptions(opts);
            result.setAiMessage(prompt);
            result.setExplanation(prompt);
            result.setNeedsExecution(false);
            return result;
        }

        if (runner.getChartUpdate() != null) {
            result.setChartUpdate(runner.getChartUpdate());
            String message = runner.getChartUpdate().getMessage();
            if (message == null || message.isBlank()) {
                message = agentResponse;
            }
            result.setAiMessage(message);
            result.setExplanation(message);
            result.setNeedsExecution(false);
            return result;
        }

        // Data-query path (Manual OR Auto): the runner already
        // produced the overview + query-option markdown.
        // Map every legacy QueryProcessingResult field so the Svelte
        // client renders byte-for-byte the same layout it did before
        // the migration. In Auto mode the runner also executed the
        // first SQL and produced a chart recommendation; in Manual
        // mode dataExecutionResult / dataChart stay null and the
        // frontend renders the "Run Query" button.
        if (runner.getDataFullResponse() != null && !runner.getDataFullResponse().isBlank()) {
            result.setAiMessage(runner.getDataFullResponse());
            if (runner.getDataGeneratedSql() != null) {
                result.setGeneratedSql(runner.getDataGeneratedSql());
            }
            result.setSchemaContext(runner.getDataSchemaContext());
            if (runner.getDataQueries() != null && !runner.getDataQueries().isEmpty()) {
                result.setQueries(runner.getDataQueries());
                String overview = runner.getDataOverview();
                List<String> notes = runner.getDataNotes();
                if (notes != null && !notes.isEmpty()) {
                    StringBuilder withNotes = new StringBuilder(overview == null ? "" : overview.trim());
                    for (String note : notes) withNotes.append("\n\n> **Data note:** ").append(note);
                    overview = withNotes.toString().trim();
                    result.setDataNotes(String.join("\n", notes));
                }
                result.setOverview(overview);
                result.setAdditionalInsightContext(runner.getAdditionalInsightSummary());
                result.setTitle(runner.getDataTitle());
                result.setExplanation(runner.getDataExplanation());
            }

            // Multi-aspect analysis path. The runner already executed
            // every aspect in parallel, recommended a chart per aspect,
            // and produced one synthesis narrative. The frontend renders
            // a card grid + synthesis section directly from
            // result.queries + result.synthesis — there is no single
            // "primary" execute result to map.
            if (runner.isMultiAspect()) {
                result.setMultiAspect(true);
                result.setSynthesisGoal(runner.getSynthesisGoal());
                result.setSynthesis(runner.getSynthesis());
                result.setNeedsExecution(false);
                result.setAiMessage(agentResponse == null || agentResponse.isBlank()
                        ? (runner.getSynthesisGoal() == null ? "Multi-aspect analysis complete." : runner.getSynthesisGoal())
                        : agentResponse);
                result.setThoughtProcess("Multi-aspect analysis: " + runner.getDataQueries().size()
                        + " aspects executed in parallel + cross-aspect synthesis.");
                return result;
            }

            ListResult<ExecuteResult> exec = runner.getDataExecutionResult();
            if (exec != null && exec.success()
                    && exec.getData() != null && !exec.getData().isEmpty()) {
                // Auto mode: execution succeeded — table + chart.
                result.setResultData(exec.getData());
                result.setNeedsExecution(false);
                result.setThoughtProcess(
                        "The user is asking about data. Analyzing the query to understand what data is needed... "
                      + "Searching for relevant database schema and tables... Generating optimized SQL query "
                      + "based on the schema context. Query executed successfully with "
                      + exec.getData().size() + " result(s).");

                var rec = runner.getDataChart();
                if (rec != null) {
                    result.setRecommendedChart(rec.getChartType().name());
                    result.setChartConfidence(rec.getConfidence());
                    result.setChartXAxis(rec.getXAxis());
                    result.setChartYAxis(rec.getYAxis());
                    result.setChartDimension(rec.getDimension());
                    result.setChartDimensions(rec.getDimensions());
                    result.setChartXAxisFormat(rec.getXAxisFormat());
                    result.setChartYAxisFormat(rec.getYAxisFormat());
                    result.setChartLineVariant(rec.getLineVariant());
                    result.setChartPieVariant(rec.getPieVariant());
                    result.setChartBarOrientation(rec.getBarOrientation());
                    result.setChartRecommendResponse(rec.getRawResponse());
                    result.setChartRecommendReason(rec.getReason());
                }
                if (followUpGenerator != null) {
                    result.setSuggestedFollowUps(
                            followUpGenerator.generate(userMessage, result, exec.getData().get(0)));
                }
            } else if (exec != null) {
                // Auto mode but execution failed.
                result.setResultData(exec.getErrorMessage());
                result.setNeedsExecution(false);
                result.setThoughtProcess(
                        "The user is asking about data. ... Query execution encountered an issue.");
            } else {
                // Manual mode: ship SQL only, wait for user to click Run.
                if (runner.getDataGeneratedSql() != null) {
                    result.setResultData("```sql\n" + runner.getDataGeneratedSql() + "\n```");
                }
                result.setNeedsExecution(true);
                result.setThoughtProcess("SQL query generated successfully. Click 'Run Query' to execute.");
            }
            return result;
        }

        // Non-data path (chat, write tools, search tools, etc.).
        result.setAiMessage(agentResponse);
        result.setExplanation(agentResponse);
        result.setNeedsExecution(false);

        if (runner.getLastSqlAttempted() != null) {
            result.setGeneratedSql(runner.getLastSqlAttempted());
        }
        return result;
    }
}
