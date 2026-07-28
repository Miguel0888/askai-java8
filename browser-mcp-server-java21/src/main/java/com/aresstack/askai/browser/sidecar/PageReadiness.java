package com.aresstack.askai.browser.sidecar;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

/**
 * Decides WHEN a page is worth reading, via a pluggable {@link PageReadinessStrategy} inspected with a
 * NON-BLOCKING {@link ReadinessProbe}. {@code page.navigate()} already blocks until {@code load}, but modern
 * result pages inject their real content afterwards, so reading immediately yields a half-built page. Playwright
 * discourages {@code networkidle} as a readiness signal, so this waits on a CONTENT condition instead.
 *
 * <p>Two entry points share the one strategy: {@link #probeOnce} is the non-blocking step the tab scheduler
 * calls per tab per tick (so a fast later tab is never stuck behind a slow earlier one); {@link #awaitBlocking}
 * is the single-page {@code open}/{@code back} path, looping the same probe on the Playwright thread until it
 * settles or the readiness deadline elapses. Readiness never fails a navigation — when the deadline expires the
 * caller reads whatever the DOM holds.</p>
 */
final class PageReadiness {

    private final PageReadinessStrategy strategy;
    private final PageReadinessPolicy policy;

    PageReadiness() {
        this(new GenericContentReadinessStrategy(), PageReadinessPolicy.defaults());
    }

    PageReadiness(PageReadinessStrategy strategy, PageReadinessPolicy policy) {
        this.strategy = strategy;
        this.policy = policy;
    }

    PageReadinessPolicy policy() {
        return policy;
    }

    /** One non-blocking inspection; {@link ReadinessLabel#PENDING} means "not yet". Used by the scheduler. */
    ReadinessLabel probeOnce(ReadinessProbe probe, ReadinessState state) {
        return strategy.inspect(probe, state, policy);
    }

    /**
     * Block on the Playwright thread until the page settles or the readiness deadline (bounded by the
     * navigation timeout) elapses. Tolerant: a PlaywrightException from a mid-navigation probe just retries
     * after the poll interval; an always-unsettled page returns when the deadline passes.
     */
    void awaitContentReady(Page page, int navigationTimeoutMillis) {
        long cap = Math.min(policy.getReadinessTimeoutMillis(),
                Math.max(policy.getPollIntervalMillis(), navigationTimeoutMillis));
        long deadline = System.currentTimeMillis() + cap;
        PlaywrightReadinessProbe probe = new PlaywrightReadinessProbe(page);
        ReadinessState state = new ReadinessState();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (probeOnce(probe, state).isSettled()) {
                    return;
                }
            } catch (PlaywrightException navigatingOrGone) {
                // Execution context destroyed mid-navigation — retry after the poll rather than failing.
            }
            try {
                page.waitForTimeout(policy.getPollIntervalMillis());
            } catch (PlaywrightException navigatingOrGone) {
                return;
            }
        }
    }
}
