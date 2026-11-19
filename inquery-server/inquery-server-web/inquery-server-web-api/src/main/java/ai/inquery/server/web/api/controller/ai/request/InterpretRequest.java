package ai.inquery.server.web.api.controller.ai.request;

import lombok.Data;

/**
 * Request for interpreting SQL query results
 */
@Data
public class InterpretRequest {

    /**
     * Original user question
     */
    private String originalQuery;

    /**
     * SQL query result data (can be table data, chart data, etc.)
     */
    private Object sqlResult;

    /**
     * AI model to use for interpretation (e.g., "gemini-3.1-flash-lite", "gpt-5.4-mini", etc.)
     */
    private String model;

    /**
     * Generated SQL query used to get the result
     */
    private String generatedSql;

    /**
     * Schema context from vector DB (table/column metadata)
     */
    private String schemaContext;

    /**
     * Business insight context (service type, platform, revenue model, etc.)
     */
    private String businessContext;

    /**
     * Additional context gathered by non-database tools before SQL execution
     * (web/wiki/Slack/Jira/GitHub summaries). Used to compare executed data
     * results against those sources after Manual Run Query.
     */
    private String additionalInsightContext;

    /**
     * Total number of rows in the original query result (before truncation for LLM)
     */
    private Integer totalRowCount;

    /**
     * Python analysis results (statistics, summaries) computed from the full dataset.
     * When present, the LLM should use these statistics instead of re-analyzing sample data.
     */
    private String pythonAnalysis;

    /**
     * Full result rows (capped by the client) used only for server-side
     * figures: totals, shares, subtotals and period changes. The LLM still
     * sees {@link #sqlResult} as the row sample.
     */
    private java.util.List<java.util.Map<String, Object>> fullRows;

    /**
     * Facts about how current the data is (partial last period, requested
     * range past the latest data), one per line, from the chat response.
     */
    private String dataNotes;

    /** Data source of the query, used to read catalog aggregation kinds (optional). */
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;
}
