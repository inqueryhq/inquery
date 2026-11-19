package ai.inquery.server.domain.api.service;

/**
 * Lightweight progress sink for AI catalog collection.
 *
 * <p>Lets long-running sub-steps inside a single table's collection (notably the
 * code-anchored GitHub event scan) report a human-readable phase label so the UI
 * doesn't sit on a bare spinner. The label may embed counts (e.g. "Matching
 * events… 540/1000"). Implementations must be cheap and thread-safe enough to be
 * called from worker threads.
 */
@FunctionalInterface
public interface CatalogCollectProgress {

    void phase(String label);

    /** No-op sink for callers that don't need progress. */
    CatalogCollectProgress NOOP = label -> { };
}
