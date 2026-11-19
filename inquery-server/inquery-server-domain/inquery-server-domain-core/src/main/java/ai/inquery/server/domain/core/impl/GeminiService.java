package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.model.ReferenceDocumentChunkHit;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM Service for extracting table metadata using multiple LLM providers.
 * Priority: Gemini > Claude > OpenAI
 */
@Slf4j
@Service
public class GeminiService {

    private final ConfigService configService;
    private final LangChainModelProvider langChainModelProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiService(ConfigService configService, LangChainModelProvider langChainModelProvider) {
        this.configService = configService;
        this.langChainModelProvider = langChainModelProvider;
        this.objectMapper = new ObjectMapper();
    }

    private static final int COLUMN_BATCH_SIZE = 50; // Process columns in batches of 50
    
    public TableMetadata extractTableMetadata(TableMetadata table, List<WikiSearchResult> wikiResults,
            List<JiraSearchResult> jiraResults, List<SlackSearchResult> slackResults,
            List<GitHubSearchResult> githubResults, List<OutlookSearchResult> outlookResults,
            List<GoogleDriveSearchResult> googleDriveResults,
            List<ReferenceDocumentChunkHit> referenceDocumentResults,
            UserAIConfigDO userConfig, String dbType, String catalogTableName, String lineageText) {

        // Get best available LLM model based on priority: Gemini > Claude > OpenAI
        String modelName = getBestAvailableModel(userConfig);
        if (modelName == null) {
            log.warn("No LLM API key is configured. Please configure Gemini, Claude, or OpenAI API key.");
            return table;
        }

        try {
            // Combine all reference content
            String allContent = combineReferenceContent(wikiResults, jiraResults, slackResults,
                    githubResults, outlookResults, googleDriveResults, referenceDocumentResults);
            
            // Check if batch processing is needed (more than COLUMN_BATCH_SIZE columns)
            List<ColumnMetadata> columns = table.getColumns();
            int totalColumns = columns.size();
            
            if (totalColumns <= COLUMN_BATCH_SIZE) {
                // Single call - no batching needed
                log.info("Processing {} columns in single LLM call", totalColumns);
                String prompt = buildPrompt(table, allContent, dbType, catalogTableName, lineageText);
                String response = callLLM(prompt, modelName);
                if (response != null) {
                    parseLLMResponse(table, response);
                }
            } else {
                // Batch processing needed
                int batchCount = (int) Math.ceil((double) totalColumns / COLUMN_BATCH_SIZE);
                log.info("Processing {} columns in {} batches (batch size: {})", totalColumns, batchCount, COLUMN_BATCH_SIZE);
                
                // Build common context (table structure summary for all batches)
                String tableContextSummary = buildTableContextSummary(table, dbType, catalogTableName);
                
                // First batch: get table description + first batch of column descriptions
                List<ColumnMetadata> firstBatch = columns.subList(0, Math.min(COLUMN_BATCH_SIZE, totalColumns));
                String firstPrompt = buildBatchPrompt(table, firstBatch, allContent, dbType, catalogTableName, true, tableContextSummary, lineageText);
                log.info("Batch 1/{}: Processing columns 1-{}", batchCount, firstBatch.size());
                String firstResponse = callLLM(firstPrompt, modelName);
                
                if (firstResponse != null) {
                    parseLLMResponse(table, firstResponse);
                }
                
                // Process remaining batches in parallel
                if (totalColumns > COLUMN_BATCH_SIZE) {
                    List<java.util.concurrent.CompletableFuture<String>> batchFutures = new java.util.ArrayList<>();
                    
                    for (int i = 1; i < batchCount; i++) {
                        int startIdx = i * COLUMN_BATCH_SIZE;
                        int endIdx = Math.min(startIdx + COLUMN_BATCH_SIZE, totalColumns);
                        List<ColumnMetadata> batch = columns.subList(startIdx, endIdx);
                        
                        final int batchNum = i + 1;
                        final String batchPrompt = buildBatchPrompt(table, batch, allContent, dbType, catalogTableName, false, tableContextSummary, lineageText);
                        
                        java.util.concurrent.CompletableFuture<String> future = 
                            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                log.info("Batch {}/{}: Processing columns {}-{}", batchNum, batchCount, startIdx + 1, endIdx);
                                return callLLM(batchPrompt, modelName);
                            });
                        batchFutures.add(future);
                    }
                    
                    // Wait for all batches and merge results
                    java.util.concurrent.CompletableFuture.allOf(batchFutures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
                    
                    for (java.util.concurrent.CompletableFuture<String> future : batchFutures) {
                        try {
                            String batchResponse = future.get();
                            java.util.Map<String, String> batchDescriptions = parseBatchColumnDescriptions(batchResponse);
                            applyColumnAggregations(table, parseJsonObjectField(batchResponse, "columnAggregations"));
                            if (batchDescriptions != null) {
                                // Merge batch descriptions into table columns
                                for (ColumnMetadata col : columns) {
                                    String desc = batchDescriptions.get(col.getColumnName());
                                    if (desc != null && !desc.isEmpty()) {
                                        col.setDescription(desc);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error getting batch result", e);
                        }
                    }
                }
                
                log.info("Batch processing completed for {} columns", totalColumns);
            }

        } catch (Exception e) {
            log.error("Error calling LLM", e);
        }

        return table;
    }
    
    /**
     * Combine all reference content from various sources
     */
    private String combineReferenceContent(List<WikiSearchResult> wikiResults,
            List<JiraSearchResult> jiraResults, List<SlackSearchResult> slackResults,
            List<GitHubSearchResult> githubResults, List<OutlookSearchResult> outlookResults,
            List<GoogleDriveSearchResult> googleDriveResults,
            List<ReferenceDocumentChunkHit> referenceDocumentResults) {
        
        // Combine wiki content
        String wikiContent = wikiResults.stream()
            .map(w -> "Title: " + w.getTitle() + "\nContent: " + w.getContent())
            .collect(Collectors.joining("\n\n---\n\n"));
        
        // Combine JIRA issue content
        String jiraContent = jiraResults.stream()
            .map(j -> String.format("Issue: %s (%s)\nTitle: %s\nStatus: %s\nDescription: %s", 
                j.getIssueKey(), j.getIssueType(), j.getSummary(), j.getStatus(), j.getDescription()))
            .collect(Collectors.joining("\n\n---\n\n"));
        
        // Combine Slack message content
        String slackContent = slackResults.stream()
            .map(s -> {
                StringBuilder sb = new StringBuilder();
                sb.append(s.getMessageText());
                if (s.getThreadMessages() != null && !s.getThreadMessages().isEmpty()) {
                    for (SlackThreadMessage thread : s.getThreadMessages()) {
                        sb.append("\n").append(thread.getMessageText());
                    }
                }
                return sb.toString();
            })
            .collect(Collectors.joining("\n\n---\n\n"));
        
        // Combine GitHub content
        String githubContent = githubResults.stream()
            .map(g -> {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Type: %s\nRepository: %s\nTitle: %s", 
                    g.getType(), g.getRepository(), g.getTitle()));
                if (g.getAuthor() != null && !g.getAuthor().isEmpty()) {
                    sb.append("\nAuthor: ").append(g.getAuthor());
                }
                if (g.getBody() != null && !g.getBody().isEmpty()) {
                    sb.append("\nContent: ").append(g.getBody());
                }
                return sb.toString();
            })
            .collect(Collectors.joining("\n\n---\n\n"));
        
        // Combine Outlook email content
        String outlookContent = outlookResults.stream()
            .map(o -> {
                StringBuilder sb = new StringBuilder();
                sb.append("Subject: ").append(o.getSubject() != null ? o.getSubject() : "");
                if (o.getBody() != null && !o.getBody().isEmpty()) {
                    sb.append("\nContent: ").append(o.getBody());
                }
                return sb.toString();
            })
            .collect(Collectors.joining("\n\n---\n\n"));

        // Combine Google Drive Docs/Sheets content
        String googleDriveContent = googleDriveResults == null ? "" :
                googleDriveResults.stream()
                        .map(g -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Title: ").append(g.getTitle() != null ? g.getTitle() : "");
                            if (g.getMimeType() != null && !g.getMimeType().isEmpty()) {
                                sb.append("\nType: ").append(g.getMimeType());
                            }
                            if (g.getUrl() != null && !g.getUrl().isEmpty()) {
                                sb.append("\nURL: ").append(g.getUrl());
                            }
                            if (g.getContent() != null && !g.getContent().isEmpty()) {
                                sb.append("\nContent: ").append(g.getContent());
                            }
                            return sb.toString();
                        })
                        .collect(Collectors.joining("\n\n---\n\n"));
        
        // Combine all content
        StringBuilder combinedContent = new StringBuilder();
        if (!wikiContent.isEmpty()) {
            combinedContent.append("=== Wiki Documents ===\n\n").append(wikiContent);
        }
        if (!jiraContent.isEmpty()) {
            if (combinedContent.length() > 0) combinedContent.append("\n\n");
            combinedContent.append("=== JIRA Issues ===\n\n").append(jiraContent);
        }
        if (!slackContent.isEmpty()) {
            if (combinedContent.length() > 0) combinedContent.append("\n\n");
            combinedContent.append("=== Slack Messages ===\n\n").append(slackContent);
        }
        if (!githubContent.isEmpty()) {
            if (combinedContent.length() > 0) combinedContent.append("\n\n");
            combinedContent.append("=== GitHub ===\n\n").append(githubContent);
        }
        if (!outlookContent.isEmpty()) {
            if (combinedContent.length() > 0) combinedContent.append("\n\n");
            combinedContent.append("=== Outlook Emails ===\n\n").append(outlookContent);
        }
        if (!googleDriveContent.isEmpty()) {
            if (combinedContent.length() > 0) combinedContent.append("\n\n");
            combinedContent.append("=== Google Drive (Docs / Sheets) ===\n\n").append(googleDriveContent);
        }

        String referenceDocContent = referenceDocumentResults == null ? "" :
                referenceDocumentResults.stream()
                        .map(r -> String.format("Document: %s (chunk %d)\n%s",
                                r.getFilename() != null ? r.getFilename() : "unknown",
                                r.getChunkIndex(),
                                r.getText() != null ? r.getText() : ""))
                        .collect(Collectors.joining("\n\n---\n\n"));
        if (!referenceDocContent.isEmpty()) {
            if (combinedContent.length() > 0) combinedContent.append("\n\n");
            combinedContent.append("=== Uploaded Reference Documents ===\n\n").append(referenceDocContent);
        }
        
        String allContent = combinedContent.toString();
        if (allContent.isEmpty()) {
            allContent = "Reference Documents: None (Please infer based on table structure and column names)";
        }
        
        // Limit content length
        if (allContent.length() > 30000) {
            allContent = allContent.substring(0, 30000) + "... (Content too long, only partial content included)";
        }
        
        return allContent;
    }
    
    /**
     * Build table context summary for batch processing (all column names + types)
     */
    private String buildTableContextSummary(TableMetadata table, String dbType, String catalogTableName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(table.getTableName()).append("\n");
        sb.append("Database Type: ").append(dbType != null ? dbType : "Unknown").append("\n");
        if (catalogTableName != null) {
            sb.append("Catalog Table Name: ").append(catalogTableName).append("\n");
        }
        sb.append("\nAll Columns (").append(table.getColumns().size()).append(" total):\n");
        for (ColumnMetadata col : table.getColumns()) {
            sb.append("- ").append(col.getColumnName()).append(" (").append(col.getDataType()).append(")\n");
        }
        return sb.toString();
    }
    
    /**
     * Build prompt for batch column processing
     */
    /**
     * Asks for an aggregation kind per numeric measure column. This is a
     * suggestion the user confirms in the catalog; lineage SQL, when present,
     * takes precedence over it.
     */
    private void appendAggregationRule(StringBuilder prompt) {
        prompt.append("AGGREGATION RULE: for each NUMERIC MEASURE column (not IDs, codes, years or other keys), "
                + "classify how it may be summed across rows:\n");
        prompt.append("   - additive: a count or amount that can be summed across rows and dates "
                + "(e.g. order count, revenue, quantity).\n");
        prompt.append("   - distinct: a distinct count of entities (e.g. active users, unique buyers) — "
                + "the same entity can appear in several rows, so a sum double counts.\n");
        prompt.append("   - non_additive: a ratio, rate, percentage, average, min/max, balance, running total "
                + "or other point-in-time value.\n");
        prompt.append("   Leave the column out (or use \"\") when the evidence is unclear. Do not guess.\n");
    }

    /** Stores valid aggregation suggestions on matching columns. */
    private void applyColumnAggregations(TableMetadata table, java.util.Map<String, String> aggregations) {
        if (aggregations == null || aggregations.isEmpty()) return;
        for (java.util.Map.Entry<String, String> e : aggregations.entrySet()) {
            String code = ai.inquery.server.domain.core.catalog.ColumnAggregation.fromCode(e.getValue()).code();
            if (code == null) continue;
            for (ColumnMetadata column : table.getColumns()) {
                if (column.getColumnName().equalsIgnoreCase(e.getKey())) {
                    column.setAggregation(code);
                    break;
                }
            }
        }
    }

    /** Reads a top-level JSON object field of an LLM response as a string map. */
    private java.util.Map<String, String> parseJsonObjectField(String response, String field) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (response == null) return out;
        try {
            String json = extractJsonFromText(response);
            if (json == null || json.isEmpty()) return out;
            JsonNode node = objectMapper.readTree(json).get(field);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception e) {
            log.debug("No {} in batch response: {}", field, e.getMessage());
        }
        return out;
    }

    private String buildBatchPrompt(TableMetadata table, List<ColumnMetadata> batchColumns, 
            String combinedContent, String dbType, String catalogTableName, 
            boolean includeTableDescription, String tableContextSummary, String lineageText) {
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate descriptions based ONLY on the provided data. Do NOT hallucinate.\n");
        prompt.append("UNITS RULE: If a column represents a measurable quantity, include the unit in the description ");
        prompt.append("when confidently inferred (time units, real/in-game currency, data size, percentage, etc.). Omit if uncertain.\n");
        appendPartitionDescriptionRule(prompt, table.getPartitionColumn());
        if (includeTableDescription) {
            prompt.append("TABLE TYPE: Classify based on data characteristics, not table name prefix. ");
            prompt.append("FACT_AGG=pre-aggregated metrics by dimensions, FACT_LOG=append-only event log, ");
            prompt.append("DIMENSION=reference/master data, SNAPSHOT=periodic full entity state capture (daily+cumulative metrics per entity per date), OTHER=none.\n");
            appendTableDescriptionRules(prompt);
        }
        prompt.append("\n");
        
        prompt.append("=== Table Context ===\n").append(tableContextSummary).append("\n");
        appendDatasetEvidence(prompt, table);

        if (table.getPartitionColumn() != null && !table.getPartitionColumn().isEmpty()) {
            prompt.append("=== Row Segmentation ===\n");
            prompt.append("Rows in this table are categorized by `").append(table.getPartitionColumn()).append("`. ");
            prompt.append("Where useful, examples are shown grouped by that column so you can see how each column's ");
            prompt.append("semantics shift across event types. Use this signal when writing descriptions.\n\n");
        }

        if (lineageText != null && !lineageText.trim().isEmpty()) {
            prompt.append("=== Lineage ===\n").append(lineageText).append("\n");
        }

        if (combinedContent != null && !combinedContent.trim().isEmpty()) {
            String truncated = combinedContent.length() > 8000 ? combinedContent.substring(0, 8000) + "..." : combinedContent;
            prompt.append("=== Reference Documents ===\n").append(truncated).append("\n\n");
        }
        
        prompt.append("=== Columns to Describe ===\n");
        for (ColumnMetadata col : batchColumns) {
            prompt.append("- ").append(col.getColumnName()).append(" (").append(col.getDataType()).append(")");
            appendColumnExamples(prompt, col, table.getPartitionColumn(), 3);
            prompt.append("\n");
        }
        
        appendAggregationRule(prompt);
        prompt.append("\n=== Output JSON ===\n");
        if (includeTableDescription) {
            prompt.append("{\n");
            prompt.append("  \"tablePurpose\": \"One concise sentence: the table's business role — no event/param lists, no date\",\n");
            prompt.append("  \"tableDescription\": \"[TYPE=FACT_AGG|FACT_LOG|DIMENSION|SNAPSHOT|OTHER] Detailed evidence-based description; must NOT repeat tablePurpose; no specific date\",\n");
            prompt.append("  \"columnDescriptions\": {\"column_name\": \"description\", ...},\n");
            prompt.append("  \"columnAggregations\": {\"numeric_column_name\": \"additive|distinct|non_additive\", ...}\n");
            prompt.append("}\n");
        } else {
            prompt.append("{\"columnDescriptions\": {\"column_name\": \"description\", ...}, ");
            prompt.append("\"columnAggregations\": {\"numeric_column_name\": \"additive|distinct|non_additive\", ...}}\n");
        }
        
        prompt.append("\nRespond ONLY with valid JSON.");
        
        return prompt.toString();
    }

    private void appendDatasetEvidence(StringBuilder prompt, TableMetadata table) {
        if (table.getDataProfileSummary() != null && !table.getDataProfileSummary().isBlank()) {
            prompt.append("=== Dataset Evidence (from live Collect — REQUIRED for tablePurpose/tableDescription) ===\n");
            prompt.append(table.getDataProfileSummary()).append("\n\n");
        }
        if (table.getSampleJson() != null && !table.getSampleJson().isBlank()) {
            String json = table.getSampleJson();
            if (json.length() > 6000) {
                json = json.substring(0, 6000) + "\n... (sample JSON truncated)";
            }
            prompt.append("=== Sample Rows (JSON) ===\n").append(json).append("\n\n");
        }
    }

    private void appendTableDescriptionRules(StringBuilder prompt) {
        prompt.append("TABLE DESCRIPTION RULES (critical for tablePurpose and tableDescription):\n");
        prompt.append("- MUST cite concrete values from Dataset Evidence and/or Sample Rows (event names, parameter keys, stream IDs, item samples).\n");
        prompt.append("- Name the top observed event_name values and what business activity they imply for THIS property.\n");
        prompt.append("- Mention notable event_params / user_properties keys when present — they define what is being tracked.\n");
        prompt.append("- Describe THIS dataset's usage (engagement, commerce, onboarding, etc.) from observed events/params, NOT generic product documentation.\n");
        prompt.append("- Do NOT lean on table-name clichés alone (e.g. \"intraday streaming\", \"raw event log\") unless supported by the evidence section.\n");
        prompt.append("- Do NOT enumerate column groups (device, privacy, LTV) without tying them to observed events or parameters.\n");
        prompt.append("- If commerce events or item fields appear, state that explicitly. If only screen_view/session_start appear, describe app engagement accordingly.\n");
        prompt.append("- PLATFORM: State the platform composition STRICTLY from the observed `platform` distribution (WEB / ANDROID / IOS, with row shares) in the evidence. ");
        prompt.append("Do NOT infer platform from which columns exist — the GA4 export schema always contains both web and app fields regardless of platform. ");
        prompt.append("Treat ANDROID + IOS as the mobile app (Firebase) and WEB as web. ");
        prompt.append("A GA4 property typically streams BOTH the mobile app and the website into this SAME table, so do NOT frame the table as platform-specific ");
        prompt.append("(\"a mobile app dataset\", \"originating from mobile apps\") just because one platform dominates the rows. ");
        prompt.append("Describe it as a unified cross-platform event table and report the composition with shares ");
        prompt.append("(e.g. \"covers both the mobile app and the website in one stream; rows are ~58% iOS, ~37% Android, ~5% web\"). ");
        prompt.append("Only if the distribution shows a single platform may the table be described as that platform only.\n");
        prompt.append("- DATE-AGNOSTIC: This may be a recurring / date-sharded table (e.g. GA4 `events_*`, `events_intraday_*`). ");
        prompt.append("Describe what the table generally contains, NOT a single day. Do NOT mention or bind the description to a specific calendar date ");
        prompt.append("even if every sample row is from one day — sample dates are just examples, not the table's scope.\n");
        prompt.append("- NO OVERLAP: `tablePurpose` is ONE short sentence (the table's business role, no event/param lists). ");
        prompt.append("`tableDescription` is the detailed evidence-based text. They MUST NOT repeat each other.\n");
    }

    /**
     * Inject the partition-aware description rule. When the source table has
     * a detected event-like column (e.g. {@code event_name}, {@code type}),
     * we want the LLM to fold per-partition semantics directly into the
     * column description — that way the whole catalog stays single-field and
     * downstream consumers (search, embeddings, UI) automatically benefit
     * without any schema migration.
     *
     * <p>The rule is conditional: tables with no partition column keep the
     * old single-sentence behavior, so dimension / snapshot tables don't get
     * artificially verbose descriptions.
     */
    private void appendPartitionDescriptionRule(StringBuilder prompt, String partitionColumn) {
        if (partitionColumn == null || partitionColumn.isEmpty()) {
            return;
        }
        prompt.append("PARTITION-AWARE DESCRIPTION RULE: This table is segmented by `").append(partitionColumn)
              .append("`. For each column whose \"Examples by `").append(partitionColumn)
              .append("`\" show that values carry DIFFERENT semantics, units, or roles across partitions, ");
        prompt.append("your description MUST embed a per-partition breakdown. Required shape:\n");
        prompt.append("  <one-sentence summary of what the column represents in general>\n");
        prompt.append("  Per ").append(partitionColumn).append(":\n");
        prompt.append("  - <partition_value_or_group>: <semantic meaning, unit, example shape>\n");
        prompt.append("  - <partition_value_or_group>: ...\n");
        prompt.append("Group partitions whose meaning is identical onto one bullet (e.g. `purchase, refund: USD amount`). ");
        prompt.append("Be comprehensive — cover every partition value that appears in the examples; description length is not a constraint. ");
        prompt.append("If ALL partitions share the same meaning, OR the column is the partition column itself, OR no grouped examples were provided for this column, ");
        prompt.append("return a normal single-sentence description WITHOUT the breakdown block. ");
        prompt.append("Use \\n to encode line breaks inside the JSON string value.\n");
    }

    /**
     * Render example values for a column into the prompt. When the table has a
     * detected partition column and this column has per-partition samples,
     * emit them grouped by partition value — that lets the LLM see how the
     * column's semantics shift across event types (e.g. {@code value} is a
     * price for {@code purchase} but a dwell time for {@code page_view}).
     * Falls back to a flat {@code Examples: a, b, c} line otherwise so columns
     * without grouped data still get the same treatment as before.
     *
     * @param prompt           prompt buffer to append to
     * @param col              column metadata (with example values populated)
     * @param partitionColumn  partition column name, or null when none detected
     * @param flatLimit        cap for the flat-list fallback
     */
    private void appendColumnExamples(StringBuilder prompt, ColumnMetadata col,
                                      String partitionColumn, int flatLimit) {
        java.util.Map<String, List<String>> grouped = col.getPartitionedExampleValues();
        boolean haveGrouped = grouped != null && !grouped.isEmpty()
                && partitionColumn != null && !partitionColumn.isEmpty();

        if (haveGrouped) {
            prompt.append(" | Examples by `").append(partitionColumn).append("`:");
            // Show every partition value the sampler collected (capped upstream
            // at PARTITION_MAX_VALUES = 30). Description-column length is not a
            // concern, so we trade some prompt size for a fully visible breakdown
            // and let the LLM consolidate identical-semantic partitions itself.
            for (java.util.Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                List<String> samples = entry.getValue();
                if (samples == null || samples.isEmpty()) continue;
                List<String> distinct = samples.stream().distinct().limit(3).collect(Collectors.toList());
                if (distinct.isEmpty()) continue;
                prompt.append("\n    ").append(entry.getKey()).append(": ")
                      .append(String.join(", ", distinct));
            }
            return;
        }

        if (col.getExampleValues() != null && !col.getExampleValues().isEmpty()) {
            prompt.append(" | Examples: ")
                  .append(col.getExampleValues().stream()
                      .limit(flatLimit)
                      .collect(Collectors.joining(", ")));
        }
    }

    /**
     * Parse batch response for column descriptions only
     */
    private java.util.Map<String, String> parseBatchColumnDescriptions(String response) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (response == null || response.isEmpty()) {
            return result;
        }
        
        try {
            // Clean response
            String jsonStr = response.trim();
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substring(7);
            } else if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.substring(3);
            }
            if (jsonStr.endsWith("```")) {
                jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
            }
            jsonStr = jsonStr.trim();
            
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode columnDescriptions = root.get("columnDescriptions");
            if (columnDescriptions != null && columnDescriptions.isObject()) {
                columnDescriptions.fields().forEachRemaining(entry -> {
                    result.put(entry.getKey(), entry.getValue().asText());
                });
            }
        } catch (Exception e) {
            log.error("Failed to parse batch column descriptions", e);
        }
        
        return result;
    }

    private String buildPrompt(TableMetadata table, String combinedContent, String dbType, String catalogTableName, String lineageText) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate metadata for this database table based ONLY on the provided data.\n\n");
        
        // BigQuery context (minimal)
        if ("BIGQUERY".equalsIgnoreCase(dbType) && catalogTableName != null && catalogTableName.contains("YYYYMMDD")) {
            prompt.append("Note: This is a BigQuery date-sharded table (pattern: ").append(catalogTableName).append(")\n\n");
        }
        
        prompt.append("=== Table Name ===\n").append(table.getTableName()).append("\n\n");
        appendDatasetEvidence(prompt, table);

        if (table.getPartitionColumn() != null && !table.getPartitionColumn().isEmpty()) {
            prompt.append("=== Row Segmentation ===\n");
            prompt.append("Rows in this table are categorized by `").append(table.getPartitionColumn()).append("`. ");
            prompt.append("Where useful, examples below are shown grouped by that column so you can see how each column's ");
            prompt.append("semantics shift across event types. Use this signal when writing descriptions.\n\n");
        }

        prompt.append("=== Columns ===\n");

        for (ColumnMetadata column : table.getColumns()) {
            prompt.append("- ").append(column.getColumnName())
                  .append(" (").append(column.getDataType()).append(")");
            appendColumnExamples(prompt, column, table.getPartitionColumn(), 5);
            prompt.append("\n");
        }

        if (lineageText != null && !lineageText.trim().isEmpty()) {
            prompt.append("\n=== Lineage ===\n");
            prompt.append(lineageText).append("\n");
        }

        if (combinedContent != null && !combinedContent.trim().isEmpty()) {
            prompt.append("\n=== Reference Documents ===\n");
            prompt.append(combinedContent);
        }
        
        prompt.append("\n\n=== Instructions ===\n");
        prompt.append("1. Describe ONLY what you can infer from the actual data above\n");
        prompt.append("2. Do NOT assume or hallucinate information not present in the data\n");
        prompt.append("3. If unsure, keep descriptions brief and factual\n");
        appendTableDescriptionRules(prompt);
        prompt.append("4. Classify table type based on the DATA CHARACTERISTICS, not the table name prefix:\n");
        prompt.append("   - FACT_AGG: Pre-aggregated metrics grouped by dimensions (e.g., daily revenue by region). Rows represent aggregated results, not raw events or entity states.\n");
        prompt.append("   - FACT_LOG: Append-only raw event/transaction log. Each row is a single immutable event with a timestamp.\n");
        prompt.append("   - DIMENSION: Slowly changing reference/master data describing an entity (e.g., user profile, product catalog).\n");
        prompt.append("   - SNAPSHOT: Periodic full capture of entity state at a point in time. Rows represent the complete state of each entity on a given date, often with both daily and cumulative metrics.\n");
        prompt.append("   - OTHER: None of the above.\n");
        prompt.append("   IMPORTANT: Prioritize actual data patterns over naming conventions. A table named FCT_* can be SNAPSHOT if it captures entity state per date.\n");
        prompt.append("5. UNITS RULE: If a column represents a measurable quantity, include the unit in the description when confidently inferred from column name, data type, or example values.\n");
        prompt.append("   - Time units (seconds, minutes, hours, days, milliseconds, etc.)\n");
        prompt.append("   - Currency or value units (real currency like USD/KRW/EUR, or virtual/internal currency like points/credits/gems, etc.)\n");
        prompt.append("   - Data size, percentage, weight, distance, or any other measurable unit\n");
        prompt.append("   - Only include when confident. If uncertain, omit the unit.\n");
        appendPartitionDescriptionRule(prompt, table.getPartitionColumn());
        appendAggregationRule(prompt);
        prompt.append("\n");
        
        prompt.append("=== Output JSON Format ===\n");
        prompt.append("{\n");
        prompt.append("  \"tablePurpose\": \"One concise sentence: the table's business role — no event/param lists, no date\",\n");
        prompt.append("  \"tableDescription\": \"[TYPE=...] Detailed evidence-based description; must NOT repeat tablePurpose; no specific date\",\n");
        prompt.append("  \"columns\": [\n");
        prompt.append("    {\"columnName\": \"name\", \"description\": \"Brief factual description\", \"aggregation\": \"additive|distinct|non_additive|\"}\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("\nRespond ONLY with valid JSON.");

        return prompt.toString();
    }

    /**
     * Get the best available LLM model name based on priority: Gemini > Claude > OpenAI
     */
    private String getBestAvailableModel(UserAIConfigDO userConfig) {
        // Priority 1: Gemini
        String geminiKey = getConfigValue(LangChainModelProvider.GEMINI_KEY);
        if (geminiKey != null && !geminiKey.isEmpty()) {
            String model = ModelMapper.getDefaultPrimaryModel();
            if (userConfig != null && userConfig.getGeminiModel() != null && !userConfig.getGeminiModel().isEmpty()) {
                model = userConfig.getGeminiModel();
            }
            log.info("Using Gemini as LLM provider (priority 1), model: {}", model);
            return model;
        }

        // Priority 2: Claude (current generation as of 2026-05)
        String claudeKey = getConfigValue(LangChainModelProvider.CLAUDE_KEY);
        if (claudeKey != null && !claudeKey.isEmpty()) {
            String model = "claude-sonnet-4-6";
            log.info("Using Claude as LLM provider (priority 2), model: {}", model);
            return model;
        }

        // Priority 3: OpenAI. Uses gpt-5.4-mini rather than gpt-5.5 — the
        //   flagship's chat-completions + function-tools incompat forces
        //   medium reasoning_effort and 30-60s latency (see
        //   LangChainModelProvider#isOpenAi55Family), and this priority
        //   path is the auto-pick for users who haven't configured a
        //   model, where fast/cheap defaults matter most.
        String openaiKey = getConfigValue(LangChainModelProvider.OPENAI_KEY);
        if (openaiKey != null && !openaiKey.isEmpty()) {
            String model = "gpt-5.4-mini";
            log.info("Using OpenAI as LLM provider (priority 3), model: {}", model);
            return model;
        }

        log.warn("No LLM API key configured");
        return null;
    }

    private String getConfigValue(String key) {
        try {
            Config config = configService.find(key).getData();
            return config != null ? config.getContent() : null;
        } catch (Exception e) {
            log.warn("Failed to get config {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Turn raw code-scan evidence (event name → call-site facts: params, platform,
     * example call) into one-sentence semantic definitions for the catalog.
     * Batched into a few LLM calls; events missing from the response are simply
     * absent so the caller can fall back to the raw evidence text.
     */
    public java.util.Map<String, String> summarizeEventDefinitions(
            java.util.Map<String, String> eventEvidence, UserAIConfigDO userConfig) {
        String intro = "You are documenting analytics events for a data catalog.\n"
                + "Below are tracking events found in the application source code, "
                + "with facts extracted from their call sites (parameters, platform, example call).\n\n"
                + "For EACH event, write ONE precise sentence defining what user interaction or "
                + "occurrence fires it — inferred from the event name, its parameters, and the call site. "
                + "Be concrete about WHAT was clicked / viewed / completed and what context the parameters add "
                + "(e.g. \"Fired when the user adds an item to the cart, carrying the item id and the UI element that triggered it.\"). "
                + "Do NOT write generic filler such as \"fired from application code\" or \"tracks a user interaction\". "
                + "Do NOT enumerate the parameter names in the sentence — explain their meaning instead.\n\n"
                + "Events:\n";
        java.util.Map<String, String> result = summarizeKeyedDefinitions(eventEvidence, intro, userConfig);
        log.info("Summarized {}/{} event definitions via LLM", result.size(),
                eventEvidence == null ? 0 : eventEvidence.size());
        return result;
    }

    /**
     * Same shape as {@link #summarizeEventDefinitions} but for event PARAMETER keys:
     * evidence maps each parameter key to the events observed sending it. Returns
     * one-sentence semantics per key.
     */
    public java.util.Map<String, String> summarizeParamDefinitions(
            java.util.Map<String, String> paramEvidence, UserAIConfigDO userConfig) {
        String intro = "You are documenting analytics event PARAMETERS for a data catalog.\n"
                + "Below are parameter keys extracted from tracking calls in the application source code, "
                + "each with the list of events that send it.\n\n"
                + "For EACH parameter, write ONE precise sentence defining what the parameter value represents — "
                + "inferred from the key name and the events that carry it "
                + "(e.g. for `item_id` sent by add_to_cart: \"Identifier of the product the user added to their cart.\"). "
                + "Do NOT write generic filler such as \"a parameter sent with events\" — state what the VALUE means. "
                + "Do NOT enumerate the event names in the sentence.\n\n"
                + "Parameters:\n";
        java.util.Map<String, String> result = summarizeKeyedDefinitions(paramEvidence, intro, userConfig);
        log.info("Summarized {}/{} parameter definitions via LLM", result.size(),
                paramEvidence == null ? 0 : paramEvidence.size());
        return result;
    }

    private java.util.Map<String, String> summarizeKeyedDefinitions(
            java.util.Map<String, String> evidence, String promptIntro, UserAIConfigDO userConfig) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (evidence == null || evidence.isEmpty()) {
            return result;
        }
        String modelName = getBestAvailableModel(userConfig);
        if (modelName == null) {
            return result;
        }

        final int batchSize = 40;
        List<java.util.Map.Entry<String, String>> entries = new java.util.ArrayList<>(evidence.entrySet());
        List<java.util.concurrent.CompletableFuture<java.util.Map<String, String>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < entries.size(); i += batchSize) {
            final List<java.util.Map.Entry<String, String>> batch =
                    entries.subList(i, Math.min(i + batchSize, entries.size()));
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                String response = callLLM(buildKeyedDefinitionPrompt(promptIntro, batch), modelName);
                return parseEventDefinitions(response);
            }));
        }
        java.util.concurrent.CompletableFuture.allOf(
                futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        for (java.util.concurrent.CompletableFuture<java.util.Map<String, String>> f : futures) {
            try {
                result.putAll(f.get());
            } catch (Exception e) {
                log.warn("Definition batch failed: {}", e.getMessage());
            }
        }
        return result;
    }

    private String buildKeyedDefinitionPrompt(String intro, List<java.util.Map.Entry<String, String>> batch) {
        StringBuilder prompt = new StringBuilder(intro);
        for (java.util.Map.Entry<String, String> e : batch) {
            prompt.append("- `").append(e.getKey()).append("`: ").append(e.getValue()).append('\n');
        }
        prompt.append("\nReturn ONLY JSON, no markdown fences:\n");
        prompt.append("{\"eventDefinitions\": {\"<name>\": \"<one sentence>\", ...}}\n");
        return prompt.toString();
    }

    private java.util.Map<String, String> parseEventDefinitions(String response) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (response == null || response.isEmpty()) {
            return result;
        }
        try {
            String jsonText = extractJsonFromText(response);
            if (jsonText == null || jsonText.isEmpty()) {
                return result;
            }
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode defs = root.get("eventDefinitions");
            if (defs != null && defs.isObject()) {
                defs.fields().forEachRemaining(entry -> {
                    String text = entry.getValue().asText("");
                    if (!text.isBlank()) {
                        result.put(entry.getKey(), text.trim());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Failed to parse event-definition response: {}", e.getMessage());
        }
        return result;
    }

    private String callLLM(String prompt, String modelName) {
        try {
            log.info("Calling LLM with model: {}", modelName);

            ChatModel chatModel = langChainModelProvider.getChatModel(modelName);
            String response = chatModel.chat(prompt);

            log.info("LLM call completed successfully");
            return response;
        } catch (Exception e) {
            log.error("Exception during LLM call with model {}: {}", modelName, e.getMessage(), e);
            return null;
        }
    }

    private void parseLLMResponse(TableMetadata table, String response) {
        if (response == null || response.isEmpty()) {
            return;
        }

        try {
            // LangChain returns text directly, extract JSON from it
            String jsonText = extractJsonFromText(response);

            if (jsonText != null && !jsonText.isEmpty()) {
                JsonNode metadata = objectMapper.readTree(jsonText);

                // Extract table information
                if (metadata.has("tablePurpose")) {
                    table.setTablePurpose(metadata.path("tablePurpose").asText(""));
                }
                if (metadata.has("tableDescription")) {
                    table.setTableDescription(metadata.path("tableDescription").asText(""));
                }

                // Extract column information - support both formats
                // Format 1: "columns": [{"columnName": "...", "description": "..."}] (buildPrompt)
                if (metadata.has("columns") && metadata.path("columns").isArray()) {
                    JsonNode columns = metadata.path("columns");

                    for (JsonNode columnNode : columns) {
                        String columnName = columnNode.path("columnName").asText("");
                        String description = columnNode.path("description").asText("");
                        String aggregation = columnNode.path("aggregation").asText("");
                        if (!aggregation.isBlank()) {
                            applyColumnAggregations(table, java.util.Map.of(columnName, aggregation));
                        }

                        // Match and add description to table columns
                        for (ColumnMetadata column : table.getColumns()) {
                            if (column.getColumnName().equalsIgnoreCase(columnName)) {
                                column.setDescription(description);
                                break;
                            }
                        }
                    }
                }
                
                if (metadata.has("columnAggregations") && metadata.path("columnAggregations").isObject()) {
                    java.util.Map<String, String> aggs = new java.util.HashMap<>();
                    metadata.path("columnAggregations").fields()
                            .forEachRemaining(e -> aggs.put(e.getKey(), e.getValue().asText("")));
                    applyColumnAggregations(table, aggs);
                }

                // Format 2: "columnDescriptions": {"column_name": "description"} (buildBatchPrompt)
                if (metadata.has("columnDescriptions") && metadata.path("columnDescriptions").isObject()) {
                    JsonNode columnDescriptions = metadata.path("columnDescriptions");
                    columnDescriptions.fields().forEachRemaining(entry -> {
                        String columnName = entry.getKey();
                        String description = entry.getValue().asText("");
                        
                        // Match and add description to table columns
                        for (ColumnMetadata column : table.getColumns()) {
                            if (column.getColumnName().equalsIgnoreCase(columnName)) {
                                // Only set if not already set (don't overwrite existing)
                                if (column.getDescription() == null || column.getDescription().isEmpty()) {
                                    column.setDescription(description);
                                }
                                break;
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            // On JSON parsing failure, use full text as table description
            table.setTableDescription("Response parsing failed: " + response.substring(0, Math.min(500, response.length())));
            log.error("Failed to parse LLM response", e);
        }
    }

    private String extractJsonFromText(String text) {
        // Find ```json ... ``` format
        int jsonStart = text.indexOf("```json");
        if (jsonStart != -1) {
            jsonStart = text.indexOf("\n", jsonStart) + 1;
            int jsonEnd = text.indexOf("```", jsonStart);
            if (jsonEnd != -1) {
                return text.substring(jsonStart, jsonEnd).trim();
            }
        }

        // Find { ... } format
        int braceStart = text.indexOf("{");
        if (braceStart != -1) {
            int braceEnd = text.lastIndexOf("}");
            if (braceEnd > braceStart) {
                return text.substring(braceStart, braceEnd + 1);
            }
        }

        return text;
    }
}

