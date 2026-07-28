package com.aresstack.askai.browser.sidecar;

/**
 * The verdict a {@link PageReadinessStrategy} returns for one non-blocking inspection. {@link #PENDING} means
 * "keep waiting"; every other value is TERMINAL for the wait — the page has reached an interpretable, settled
 * state and is worth reading. The specific settled reason travels to the caller as {@code readinessStatus} so
 * a research loop can tell real results from an empty page or a bot-challenge.
 */
enum ReadinessLabel {

    /** Not settled yet — inspect again after the poll interval. */
    PENDING(false),
    /** A results container with content is present (search pages). */
    RESULTS(true),
    /** The page explicitly says it has no results (search pages) — settled, just empty. */
    NO_RESULTS(true),
    /** A consent wall / captcha / bot-challenge is showing — settled, but not usable evidence. */
    CHALLENGE(true),
    /** Generic content has appeared and stopped growing (the fallback for ordinary pages). */
    CONTENT_STABLE(true);

    private final boolean settled;

    ReadinessLabel(boolean settled) {
        this.settled = settled;
    }

    /** True once waiting should stop and the page be read. */
    boolean isSettled() {
        return settled;
    }
}
