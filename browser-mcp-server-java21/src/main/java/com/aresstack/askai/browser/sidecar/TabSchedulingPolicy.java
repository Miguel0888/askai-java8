package com.aresstack.askai.browser.sidecar;

/**
 * How aggressively background tabs may be opened — enforced IN the backend, never merely requested by the
 * caller. A tool-supplied concurrency is only a wish and is always clamped to {@link #maxConcurrentNavigations}
 * ({@link #effectiveConcurrency}); the model can never raise the ceiling. Distinct from {@link PageReadinessPolicy},
 * which governs WHEN a single page is considered ready — this one governs HOW MANY load at once and how spaced.
 */
final class TabSchedulingPolicy {

    private final int maxOpenTabs;
    private final int maxConcurrentNavigations;
    private final int maxBatchUrls;
    private final long navigationStaggerMillis;

    TabSchedulingPolicy(int maxOpenTabs, int maxConcurrentNavigations, int maxBatchUrls,
                        long navigationStaggerMillis) {
        this.maxOpenTabs = maxOpenTabs;
        this.maxConcurrentNavigations = maxConcurrentNavigations;
        this.maxBatchUrls = maxBatchUrls;
        this.navigationStaggerMillis = navigationStaggerMillis;
    }

    static TabSchedulingPolicy defaults() {
        return new TabSchedulingPolicy(4, 3, 16, 300);
    }

    int getMaxOpenTabs() {
        return maxOpenTabs;
    }

    int getMaxConcurrentNavigations() {
        return maxConcurrentNavigations;
    }

    int getMaxBatchUrls() {
        return maxBatchUrls;
    }

    long getNavigationStaggerMillis() {
        return navigationStaggerMillis;
    }

    /** The caller's requested concurrency, never above the configured ceiling and never below one. */
    int effectiveConcurrency(int requested) {
        if (requested <= 0) {
            return maxConcurrentNavigations;
        }
        return Math.min(requested, maxConcurrentNavigations);
    }
}
