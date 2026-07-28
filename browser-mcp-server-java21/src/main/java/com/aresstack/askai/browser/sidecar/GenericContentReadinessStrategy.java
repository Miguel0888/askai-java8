package com.aresstack.askai.browser.sidecar;

/**
 * The universal fallback: a page is ready once its rendered body text has reached a minimum readable size and
 * then stopped growing across the settle window. This tolerates post-{@code load} lazy content (the body keeps
 * growing while scripts inject content, so we keep waiting) without depending on {@code networkidle}, which
 * Playwright discourages. Non-blocking — one inspection per call, cadence owned by the scheduler / blocking
 * wait.
 */
final class GenericContentReadinessStrategy implements PageReadinessStrategy {

    @Override
    public ReadinessLabel inspect(ReadinessProbe probe, ReadinessState state, PageReadinessPolicy policy) {
        long length = probe.bodyTextLength();
        boolean bigEnough = length >= policy.getMinimumReadableCharacters();
        if (bigEnough && length == state.previousLength) {
            if (++state.stablePolls >= policy.getSettlePollCount()) {
                return ReadinessLabel.CONTENT_STABLE;
            }
        } else {
            // Still growing (or still too small): reset the settle window and remember the new size.
            state.stablePolls = 0;
            state.previousLength = length;
        }
        return ReadinessLabel.PENDING;
    }
}
