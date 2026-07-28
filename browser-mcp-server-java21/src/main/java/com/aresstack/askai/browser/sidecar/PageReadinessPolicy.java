package com.aresstack.askai.browser.sidecar;

/**
 * WHEN a single page counts as ready to read. Separate from {@link TabSchedulingPolicy} (how many tabs load at
 * once). {@link #pollIntervalMillis} is both the probe cadence and the width of the settle window;
 * {@link #settlePollCount} equal polls of non-shrinking body text ⇒ stable. {@link #readinessTimeoutMillis} is
 * the per-tab absolute deadline — distinct from the caller's await-timeout: a page that never settles is
 * TIMED_OUT and closed, whereas an await-timeout merely stops waiting and leaves every tab loading.
 */
final class PageReadinessPolicy {

    private final long pollIntervalMillis;
    private final int settlePollCount;
    private final long readinessTimeoutMillis;
    private final int minimumReadableCharacters;

    PageReadinessPolicy(long pollIntervalMillis, int settlePollCount, long readinessTimeoutMillis,
                        int minimumReadableCharacters) {
        this.pollIntervalMillis = pollIntervalMillis;
        this.settlePollCount = settlePollCount;
        this.readinessTimeoutMillis = readinessTimeoutMillis;
        this.minimumReadableCharacters = minimumReadableCharacters;
    }

    static PageReadinessPolicy defaults() {
        return new PageReadinessPolicy(250, 2, 6_000, 48);
    }

    long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    int getSettlePollCount() {
        return settlePollCount;
    }

    long getReadinessTimeoutMillis() {
        return readinessTimeoutMillis;
    }

    int getMinimumReadableCharacters() {
        return minimumReadableCharacters;
    }
}
