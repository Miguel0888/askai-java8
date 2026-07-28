package com.aresstack.askai.browser.sidecar;

/**
 * Lifecycle of one background tab. Forward-only; every terminal state ({@link #COMPLETED}, {@link #TIMED_OUT},
 * {@link #FAILED}, {@link #CLOSED}) stops all further scheduling for that tab, so a late readiness signal from
 * an already-finished page is ignored rather than acted on.
 *
 * <pre>
 *   QUEUED ─▶ NAVIGATING ─▶ LOADING ─▶ READY ─▶ READING ─▶ COMPLETED
 *                              │          │
 *                              ├──────────┴──▶ TIMED_OUT   (per-tab deadline)
 *                              ├─────────────▶ FAILED      (technical error)
 *                              └─────────────▶ CLOSED      (cancel / batch close / recovery)
 * </pre>
 */
enum TabState {

    QUEUED,
    NAVIGATING,
    LOADING,
    READY,
    READING,
    COMPLETED,
    TIMED_OUT,
    FAILED,
    CLOSED;

    /** True once the tab is finished and must never be scheduled or probed again. */
    boolean isTerminal() {
        return this == COMPLETED || this == TIMED_OUT || this == FAILED || this == CLOSED;
    }

    /** True while the tab still holds an open page occupying an {@code maxOpenTabs} slot. */
    boolean holdsOpenPage() {
        return this == NAVIGATING || this == LOADING || this == READY || this == READING;
    }

    /** True while the tab is still loading and counts against {@code maxConcurrentNavigations}. */
    boolean isInFlight() {
        return this == NAVIGATING || this == LOADING;
    }
}
