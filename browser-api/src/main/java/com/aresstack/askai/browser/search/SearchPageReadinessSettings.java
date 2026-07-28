package com.aresstack.askai.browser.search;

/**
 * Content-readiness timing for search/target pages. Four DISTINCT clocks — deliberately separate
 * fields, never one shared "timeout": the MCP await budget, the page readiness deadline, the
 * navigation commit timeout, and (NOT here — it has none by invariant) the CAPTCHA wait.
 */
public final class SearchPageReadinessSettings {

    /** Probe cadence and settle-window width. */
    public final int pollIntervalMillis;
    /** Consecutive equal-content polls required to declare the page stable. */
    public final int settlePollCount;
    /** Minimum body text size before the stability check begins. */
    public final int minimumReadableCharacters;
    /** Per-page absolute deadline for content readiness. */
    public final int contentReadinessTimeoutMillis;
    /** Timeout for the navigation itself to commit. */
    public final int navigationCommitTimeoutMillis;
    /** Upper bound of a single MCP await call (the caller re-awaits; readiness may span calls). */
    public final int maximumAwaitCallMillis;

    public SearchPageReadinessSettings(int pollIntervalMillis, int settlePollCount,
                                       int minimumReadableCharacters, int contentReadinessTimeoutMillis,
                                       int navigationCommitTimeoutMillis, int maximumAwaitCallMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
        this.settlePollCount = settlePollCount;
        this.minimumReadableCharacters = minimumReadableCharacters;
        this.contentReadinessTimeoutMillis = contentReadinessTimeoutMillis;
        this.navigationCommitTimeoutMillis = navigationCommitTimeoutMillis;
        this.maximumAwaitCallMillis = maximumAwaitCallMillis;
    }
}
