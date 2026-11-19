package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.api.service.CatalogCollectProgress;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.core.impl.GitHubSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GitHub search service
 * Adapted from data-category project
 */
public class GitHubService extends AbstractSearchService {

    private final UserAIConfigDO userConfig;

    public GitHubService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl(userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl()
                : "https://api.github.com")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json")
            .build());
        this.userConfig = userConfig;
    }

    public GitHubService(UserAIConfigSaveParam config) {
        this(toDO(config));
    }

    private static UserAIConfigDO toDO(UserAIConfigSaveParam config) {
        UserAIConfigDO d = new UserAIConfigDO();
        d.setGithubToken(config.getGithubToken());
        d.setGithubBaseUrl(config.getGithubBaseUrl());
        d.setGithubOrganization(config.getGithubOrganization());
        return d;
    }

    public boolean isConfigured() {
        return userConfig != null
                && userConfig.getGithubToken() != null
                && !userConfig.getGithubToken().isBlank();
    }

    public List<GitHubSearchResult> searchCode(String tableName, int maxResults) {
        List<GitHubSearchResult> results = new ArrayList<>();

        if (userConfig.getGithubToken() == null || userConfig.getGithubToken().isEmpty()) {
            logger.warn("GitHub token is not configured.");
            return results;
        }

        try {
            String tableNameOnly = extractTableName(tableName);
            logger.info("GitHub code search started: {} (original: {}, query: {})", tableName, tableNameOnly, tableNameOnly);

            // Pass 1: filename-based search (filename: query) - top 1 only
            logger.info("GitHub pass 1: filename search (filename:{})", tableNameOnly);
            results = searchCodeByFilename(tableNameOnly, 1);
            logger.info("GitHub pass 1 (filename) done: {} results", results.size());
            
            // Pass 2: code content search (fill remaining slots)
            int remainingResults = maxResults - results.size();
            if (remainingResults > 0) {
                logger.info("GitHub pass 2: code search (query: {}, max: {})", tableNameOnly, remainingResults);
                List<GitHubSearchResult> codeResults = searchCodeInternal(tableNameOnly, remainingResults);
                results.addAll(codeResults);
                logger.info("GitHub pass 2 (code) done: {} added", codeResults.size());
            }

            logger.info("GitHub code search complete: {} results", results.size());
        } catch (Exception e) {
            logger.error("Error during GitHub code search", e);
        }

        return results;
    }

    /**
     * Search GitHub code by file name.
     * Uses a filename: query to find files whose names contain the table name.
     */
    private List<GitHubSearchResult> searchCodeByFilename(String tableName, int maxResults) {
        List<GitHubSearchResult> results = new ArrayList<>();

        try {
            // Build filename search query (filename:)
            StringBuilder query = new StringBuilder();
            query.append("filename:").append(tableName);
            
            if (userConfig.getGithubOrganization() != null && !userConfig.getGithubOrganization().isEmpty()) {
                query.append(" org:").append(userConfig.getGithubOrganization());
            }

            logger.info("GitHub filename search query: {}", query.toString());

            String baseUrl = userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl()
                : "https://api.github.com";

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(SEARCH_BUFFER)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json");

            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }

            WebClient configuredWebClient = builder.build();

            String response = configuredWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search/code")
                    .queryParam("q", query.toString())
                    .queryParam("per_page", maxResults)
                    .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();
                        return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> {
                                logger.error("GitHub filename search API error (HTTP {}): {}", statusCode, errorBody);
                                return Mono.error(new RuntimeException("GitHub filename search API failed: " + statusCode));
                            });
                    })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .onErrorResume(e -> {
                    logger.error("GitHub filename search API call failed: {}", e.getMessage());
                    return Mono.just("{\"items\":[]}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);

                int totalCount = root.path("total_count").asInt(0);
                logger.info("GitHub filename search response: total_count={}", totalCount);

                JsonNode items = root.path("items");

                for (JsonNode item : items) {
                    String name = item.path("name").asText("");
                    String path = item.path("path").asText("");
                    String htmlUrl = item.path("html_url").asText("");

                    JsonNode repo = item.path("repository");
                    String repository = repo.path("full_name").asText("");

                    String snippet = "";
                    logger.debug("Fetching file content directly (filename search): {}", path);
                    snippet = fetchFileContent(repository, path, baseUrl);

                    if (snippet != null) {
                        snippet = snippet.replaceAll("<[^>]+>", "");
                        snippet = snippet.replaceAll("[ \\t]+", " ");
                        snippet = snippet.replaceAll("\n{3,}", "\n\n");
                        snippet = snippet.trim();
                    }

                    GitHubSearchResult result = new GitHubSearchResult(
                        "code", path + " (" + name + ")", snippet, htmlUrl, repository, "", "", ""
                    );
                    results.add(result);
                }
            }
        } catch (Exception e) {
            logger.error("Error during GitHub filename search", e);
        }

        return results;
    }

    private List<GitHubSearchResult> searchCodeInternal(String searchQuery, int maxResults) {
        List<GitHubSearchResult> results = new ArrayList<>();

        try {
            String query = buildQuery(searchQuery, "code");

            logger.info("GitHub code search query: {}", query);

            String baseUrl = userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl()
                : "https://api.github.com";

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(SEARCH_BUFFER)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json");

            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }

            WebClient configuredWebClient = builder.build();

            String response = configuredWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search/code")
                    .queryParam("q", query)
                    .queryParam("per_page", maxResults)
                    .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();
                        return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> {
                                logger.error("GitHub Code API error (HTTP {}): {}", statusCode, errorBody);
                                return Mono.error(new RuntimeException("GitHub Code API failed: " + statusCode));
                            });
                    })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .onErrorResume(e -> {
                    logger.error("GitHub Code API call failed: {}", e.getMessage());
                    return Mono.just("{\"items\":[]}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);

                int totalCount = root.path("total_count").asInt(0);
                logger.info("GitHub code search response: total_count={}", totalCount);

                JsonNode items = root.path("items");
                logger.info("GitHub code search response: items array size={}", items.size());

                if (items.size() == 0 && totalCount > 0) {
                    logger.warn("GitHub code search: total_count={} but items empty. Response snippet: {}",
                        totalCount, response.substring(0, Math.min(500, response.length())));
                }

                for (JsonNode item : items) {
                    String name = item.path("name").asText("");
                    String path = item.path("path").asText("");
                    String htmlUrl = item.path("html_url").asText("");

                    JsonNode repo = item.path("repository");
                    String repository = repo.path("full_name").asText("");

                    String snippet = "";
                    JsonNode textMatches = item.path("text_matches");

                    logger.debug("Fetching file content directly: {}", path);
                    snippet = fetchFileContent(repository, path, baseUrl);

                    if ((snippet == null || snippet.isEmpty()) && textMatches.isArray() && textMatches.size() > 0) {
                        snippet = textMatches.get(0).path("fragment").asText("");
                        logger.debug("Using snippet (fallback): {} (length: {})", path, snippet.length());
                    }

                    if (snippet != null) {
                        snippet = snippet.replaceAll("<[^>]+>", "");
                        snippet = snippet.replaceAll("[ \\t]+", " ");
                        snippet = snippet.replaceAll("\n{3,}", "\n\n");
                        snippet = snippet.trim();
                    }

                    GitHubSearchResult result = new GitHubSearchResult(
                        "code", path + " (" + name + ")", snippet, htmlUrl, repository, "", "", ""
                    );
                    results.add(result);
                }
            }
        } catch (Exception e) {
            logger.error("Error during GitHub code search", e);
        }

        return results;
    }

    private String fetchFileContent(String repository, String path, String baseUrl) {
        try {
            String url = "/repos/" + repository + "/contents/" + path;

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(SEARCH_BUFFER)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3.text-match+json");

            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }

            WebClient configuredWebClient = builder.build();

            String response = configuredWebClient.get()
                .uri(url)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();
                        logger.debug("Failed to fetch file content (HTTP {}): {}", statusCode, path);
                        return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(new RuntimeException("Failed to fetch file content: " + statusCode)));
                    })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    logger.debug("Failed to fetch file content: {}", e.getMessage());
                    return Mono.just("{\"content\":\"\"}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                try {
                    JsonNode root = objectMapper.readTree(response);

                    String encoding = root.path("encoding").asText("");
                    String content = root.path("content").asText("");

                    if (!content.isEmpty()) {
                        if ("base64".equalsIgnoreCase(encoding)) {
                            String cleanContent = content.replaceAll("\\s+", "");
                            byte[] decoded = java.util.Base64.getDecoder().decode(cleanContent);
                            return new String(decoded, StandardCharsets.UTF_8);
                        } else {
                            logger.debug("File content is not base64 (encoding: {}): {}", encoding, path);
                            return content;
                        }
                    }
                } catch (IllegalArgumentException e) {
                    logger.debug("Base64 decode failed (invalid format): {} - {}", path, e.getMessage());
                } catch (Exception e) {
                    logger.debug("File content decode failed: {} - {}", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.debug("Error fetching file content: {}", e.getMessage());
        }

        return "";
    }

    private String buildQuery(String searchQuery, String type) {
        StringBuilder query = new StringBuilder();
        query.append(searchQuery);

        if (userConfig.getGithubOrganization() != null && !userConfig.getGithubOrganization().isEmpty()) {
            query.append(" org:").append(userConfig.getGithubOrganization());
        }

        return query.toString();
    }

    private String normalizeSearchQuery(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }

        String normalized = query.replace("_", " ").replace(".", " ");
        normalized = normalized.replaceAll("\\s+", " ");

        return normalized.trim();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Event-table code-anchored search
    //
    // For event/GA tables the table name (e.g. `events_*`) is useless to search;
    // the meaning lives in the event VALUES (`add_to_cart`), which
    // appear in application code as gtag('event', NAME, {...}),
    // trackEvent(NAME, {...}), logEvent(analytics, NAME, {...}), etc.
    //
    // Searching per event would mean one API call per event (1000+ events =
    // rate-limit death). Instead we flip it: locate the tracking call sites with
    // a few org-wide pattern searches, fetch those files once, parse ALL tracking
    // calls locally, and match them against the known event_name set. This scales
    // to any number of events because matching is local, and it is wrapper-
    // agnostic because the anchor is the event-name literal we already have from
    // BigQuery.
    // ───────────────────────────────────────────────────────────────────────

    /** Patterns used only to LOCATE files containing analytics calls (not per-event). */
    private static final List<String> TRACKING_LOCATE_PATTERNS = Arrays.asList(
            "trackEvent", "logEvent", "logCustomEvent", "gtag", "dataLayer.push");

    /**
     * A /search/code page with per_page=100 is ~600KB (each item embeds full repository
     * metadata) — far over WebClient's 256KB default in-memory limit, which made every
     * locate page fail (DataBufferLimitException) and the scan silently return 0 files.
     */
    private static final ExchangeStrategies SEARCH_BUFFER = ExchangeStrategies.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build();

    /** GitHub code search allows 10 requests/min — space consecutive locate calls. */
    private static final long CODE_SEARCH_MIN_INTERVAL_MS = 6_500;
    private final java.util.concurrent.atomic.AtomicLong lastCodeSearchAt = new java.util.concurrent.atomic.AtomicLong();

    private void throttleCodeSearch() {
        long last = lastCodeSearchAt.get();
        long wait = last == 0 ? 0 : CODE_SEARCH_MIN_INTERVAL_MS - (System.currentTimeMillis() - last);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCodeSearchAt.set(System.currentTimeMillis());
    }

    private static final Pattern GTAG_CALL = Pattern.compile(
            "gtag\\s*\\(\\s*['\"]event['\"]\\s*,\\s*['\"]([\\w.\\-]+)['\"]");
    private static final Pattern DATALAYER_CALL = Pattern.compile(
            "dataLayer\\.push\\s*\\(\\s*\\{[^}]*?event\\s*:\\s*['\"]([\\w.\\-]+)['\"]");
    /** Generic wrapper calls: trackEvent / logEvent / logCustomEvent / .track, with an optional first arg (e.g. analytics). */
    private static final Pattern WRAPPER_CALL = Pattern.compile(
            "(?:\\b(?:trackEvent|logEvent|logCustomEvent)|\\.track)\\s*\\(\\s*(?:[A-Za-z_$][\\w$.]*\\s*,\\s*)?['\"]([\\w.\\-]+)['\"]");
    private static final Pattern OBJ_KEY = Pattern.compile("(?:[,{\\[\\s])([A-Za-z_$][\\w$]*)\\s*:");
    private static final Pattern QUOTED_KEY = Pattern.compile("['\"]([\\w.\\-]+)['\"]\\s*:");

    /**
     * Scan the configured GitHub organization for the definitions of known event
     * names, returning one evidence result per matched event (params + platform +
     * example call site) for use as LLM context in catalog collection.
     *
     * @param knownEventNames event_name values already collected from the data source
     * @param maxFiles        cap on candidate files fetched (bounds cost; events are unbounded)
     */
    public List<GitHubSearchResult> searchEventDefinitions(Collection<String> knownEventNames, int maxFiles) {
        return searchEventDefinitions(knownEventNames, maxFiles, CatalogCollectProgress.NOOP);
    }

    public List<GitHubSearchResult> searchEventDefinitions(Collection<String> knownEventNames, int maxFiles,
            CatalogCollectProgress progress) {
        final CatalogCollectProgress report = progress != null ? progress : CatalogCollectProgress.NOOP;
        List<GitHubSearchResult> results = new ArrayList<>();
        if (!isConfigured() || knownEventNames == null || knownEventNames.isEmpty()) {
            return results;
        }
        if (userConfig.getGithubOrganization() == null || userConfig.getGithubOrganization().isBlank()) {
            logger.warn("GitHub event scan skipped: organization not configured (would search all of GitHub).");
            return results;
        }

        Set<String> known = new HashSet<>();
        for (String n : knownEventNames) {
            if (n != null && !n.isBlank()) {
                known.add(n.trim());
            }
        }
        if (known.isEmpty()) {
            return results;
        }

        String baseUrl = userConfig.getGithubBaseUrl() != null && !userConfig.getGithubBaseUrl().isEmpty()
                ? userConfig.getGithubBaseUrl() : "https://api.github.com";

        try {
            // 1) Locate ALL candidate files across the org (paginated pattern searches, NOT per-event).
            //    We never top-N the events; matching is local, so completeness is bounded only by the
            //    number of tracking files, which we scan fully (up to maxFiles as a safety bound).
            report.phase("Locating event tracking code in repositories…");
            LinkedHashSet<String> files = new LinkedHashSet<>();
            for (String pat : TRACKING_LOCATE_PATTERNS) {
                for (String[] rp : searchFilePaths(pat, baseUrl, maxFiles)) {
                    files.add(rp[0] + "\n" + rp[1]);
                }
                if (files.size() >= maxFiles) break;
            }
            logger.info("GitHub event scan: located {} candidate files for {} known events", files.size(), known.size());
            if (files.isEmpty()) {
                // Distinguish "no tracking code in the org" from a silently failing search
                // (rate limit, bad token, oversized response) — both used to look identical.
                logger.warn("GitHub event scan: code search returned no tracking files for org '{}' — "
                        + "check earlier 'event-locate search failed' warnings (rate limit/token/buffer).",
                        userConfig.getGithubOrganization());
                report.phase("GitHub code search found no tracking files (check token/org)");
                return results;
            }

            // 2) Fetch files CONCURRENTLY, parse all tracking calls, match locally to known events.
            //    Local matching scales to any number of events (1000+); cost is bounded by file count.
            Map<String, EventEvidence> evidence = new java.util.concurrent.ConcurrentHashMap<>();
            final int totalFiles = files.size();
            final java.util.concurrent.atomic.AtomicInteger scanned = new java.util.concurrent.atomic.AtomicInteger();
            int parallelism = Math.min(12, Math.max(1, totalFiles));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism);
            try {
                List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
                for (String f : files) {
                    final String fileEntry = f;
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        int nl = fileEntry.indexOf('\n');
                        String repo = fileEntry.substring(0, nl);
                        String path = fileEntry.substring(nl + 1);
                        String content = fetchFileContent(repo, path, baseUrl);
                        int done = scanned.incrementAndGet();
                        report.phase(String.format("Scanning code for event definitions… %d/%d files (%d events matched)",
                                done, totalFiles, evidence.size()));
                        if (content == null || content.isEmpty()) {
                            return;
                        }
                        String platform = platformHint(path, content);
                        String source = repo + "/" + path;
                        for (ExtractedCall call : extractTrackingCalls(content)) {
                            if (!known.contains(call.eventName)) {
                                continue;
                            }
                            EventEvidence ev = evidence.computeIfAbsent(call.eventName, EventEvidence::new);
                            synchronized (ev) {
                                ev.params.addAll(call.params);
                                if (platform != null) {
                                    ev.platforms.add(platform);
                                }
                                ev.sources.add(source);
                                if (ev.snippet == null && call.snippet != null && !call.snippet.isEmpty()) {
                                    ev.snippet = call.snippet;
                                }
                            }
                        }
                    }, pool));
                }
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            } finally {
                pool.shutdown();
            }

            // 3) One evidence result per matched event (reuses the existing GitHub prompt path).
            for (EventEvidence ev : evidence.values()) {
                results.add(ev.toResult());
            }
            logger.info("GitHub event scan: matched {} of {} known events from {} files",
                    results.size(), known.size(), files.size());
        } catch (Exception e) {
            logger.error("Error during GitHub event-definition scan", e);
        }
        return results;
    }

    /**
     * Code search returning (repository, path) pairs — used to locate tracking files.
     * Paginates through all result pages (GitHub caps code search at 1000 results / 100 per page)
     * up to {@code maxResults}, so we find every tracking file, not just the first page.
     */
    private List<String[]> searchFilePaths(String pattern, String baseUrl, int maxResults) {
        List<String[]> paths = new ArrayList<>();
        try {
            String query = buildQuery(pattern, "code");

            WebClient.Builder builder = WebClient.builder()
                    .baseUrl(baseUrl)
                    .exchangeStrategies(SEARCH_BUFFER)
                    .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json");
            if (userConfig.getGithubToken() != null && !userConfig.getGithubToken().isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + userConfig.getGithubToken());
            }
            WebClient client = builder.build();

            final int perPage = 100; // GitHub max page size for search
            int maxPages = Math.max(1, (int) Math.ceil(maxResults / (double) perPage));
            for (int page = 1; page <= maxPages; page++) {
                final int pageNo = page;
                throttleCodeSearch();
                String response = client.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/search/code")
                                .queryParam("q", query)
                                .queryParam("per_page", perPage)
                                .queryParam("page", pageNo)
                                .build())
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(30))
                        .retryWhen(Retry.backoff(2, Duration.ofSeconds(2)))
                        .onErrorResume(e -> {
                            logger.warn("GitHub event-locate search failed for '{}' page {}: {}", pattern, pageNo, e.getMessage());
                            return Mono.just("{\"items\":[]}");
                        })
                        .block();

                if (response == null || response.isEmpty()) {
                    break;
                }
                JsonNode root = objectMapper.readTree(response);
                JsonNode items = root.path("items");
                int pageCount = 0;
                for (JsonNode item : items) {
                    String path = item.path("path").asText("");
                    String repo = item.path("repository").path("full_name").asText("");
                    if (!path.isEmpty() && !repo.isEmpty()) {
                        paths.add(new String[]{repo, path});
                    }
                    pageCount++;
                }
                // Last page reached (fewer than a full page) or hit the requested cap.
                if (pageCount < perPage || paths.size() >= maxResults) {
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("GitHub event-locate search error for '{}': {}", pattern, e.getMessage());
        }
        return paths;
    }

    private List<ExtractedCall> extractTrackingCalls(String content) {
        List<ExtractedCall> calls = new ArrayList<>();
        collectMatches(calls, content, GTAG_CALL);
        collectMatches(calls, content, DATALAYER_CALL);
        collectMatches(calls, content, WRAPPER_CALL);
        return calls;
    }

    private void collectMatches(List<ExtractedCall> calls, String content, Pattern pattern) {
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            if (name == null || name.isBlank()) {
                continue;
            }
            Set<String> params = extractParamKeysAfter(content, m.end());
            String snippet = lineAround(content, m.start());
            calls.add(new ExtractedCall(name.trim(), params, snippet));
        }
    }

    /** Extract parameter keys from the object/dict literal that follows the event name. */
    private Set<String> extractParamKeysAfter(String content, int fromIndex) {
        Set<String> keys = new LinkedHashSet<>();
        int n = content.length();
        int limit = Math.min(n, fromIndex + 60);
        int start = -1;
        char open = 0, close = 0;
        for (int i = fromIndex; i < limit; i++) {
            char c = content.charAt(i);
            if (c == '{') { start = i; open = '{'; close = '}'; break; }
            if (c == '[') { start = i; open = '['; close = ']'; break; }
            if (c == ')') { break; } // call closed without a params object
        }
        if (start < 0) {
            return keys;
        }
        int depth = 0, end = -1;
        int scanLimit = Math.min(n, start + 2000);
        for (int j = start; j < scanLimit; j++) {
            char c = content.charAt(j);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) { end = j; break; }
            }
        }
        if (end < 0) {
            end = scanLimit;
        }
        String block = content.substring(start, end);
        Matcher qk = QUOTED_KEY.matcher(block);
        while (qk.find()) {
            keys.add(qk.group(1));
        }
        Matcher ok = OBJ_KEY.matcher(block);
        while (ok.find()) {
            keys.add(ok.group(1));
        }
        keys.remove("event"); // dataLayer's own discriminator, not a real parameter
        if (keys.size() > 40) {
            return new LinkedHashSet<>(new ArrayList<>(keys).subList(0, 40));
        }
        return keys;
    }

    private String lineAround(String content, int index) {
        int start = content.lastIndexOf('\n', Math.max(0, index - 1));
        int end = content.indexOf('\n', index);
        start = (start < 0) ? 0 : start + 1;
        if (end < 0) {
            end = content.length();
        }
        return content.substring(start, Math.min(end, start + 240)).trim();
    }

    private String platformHint(String path, String content) {
        String p = path.toLowerCase();
        if (p.endsWith(".swift")) {
            return "iOS";
        }
        if (p.endsWith(".kt") || p.endsWith(".java")) {
            return "Android";
        }
        if (content.contains("gtag(") || content.contains("dataLayer")) {
            return "web";
        }
        if (content.contains("firebase") || content.contains("logEvent")) {
            return "app (Firebase)";
        }
        if (p.endsWith(".ts") || p.endsWith(".tsx") || p.endsWith(".js") || p.endsWith(".jsx")
                || p.endsWith(".vue") || p.endsWith(".svelte")) {
            return "web";
        }
        return null;
    }

    private static final class ExtractedCall {
        final String eventName;
        final Set<String> params;
        final String snippet;

        ExtractedCall(String eventName, Set<String> params, String snippet) {
            this.eventName = eventName;
            this.params = params;
            this.snippet = snippet;
        }
    }

    private static final class EventEvidence {
        final String eventName;
        final Set<String> params = new LinkedHashSet<>();
        final Set<String> platforms = new LinkedHashSet<>();
        final Set<String> sources = new LinkedHashSet<>();
        String snippet;

        EventEvidence(String eventName) {
            this.eventName = eventName;
        }

        GitHubSearchResult toResult() {
            StringBuilder b = new StringBuilder();
            b.append("Event `").append(eventName).append("` is fired from application code.");
            if (!platforms.isEmpty()) {
                b.append(" Platform(s): ").append(String.join(", ", platforms)).append('.');
            }
            if (!params.isEmpty()) {
                b.append(" Parameters passed with this event: ").append(String.join(", ", params)).append('.');
            }
            if (snippet != null && !snippet.isEmpty()) {
                b.append(" Example call site: ").append(snippet);
            }
            String repo = sources.isEmpty() ? "" : sources.stream().limit(3).collect(Collectors.joining(", "));
            return new GitHubSearchResult("event-definition", eventName, b.toString(), "", repo, "", "", "");
        }
    }
}







