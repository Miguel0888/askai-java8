package com.aresstack.askai.browser.sidecar;

/**
 * The result of ONE cooperative scheduler tick for a batch. {@link Outcome#PENDING} means the batch is still
 * making progress (keep ticking); the caller — not the manager — decides when its own await-budget has elapsed
 * and turns repeated PENDING into a WAIT_TIMEOUT that closes NO tabs. The terminal outcomes are produced by the
 * manager: {@link Outcome#READY} carries an atomically read {@link ReadyTab}; {@link Outcome#DRAINED} means the
 * batch is exhausted; {@link Outcome#BATCH_FAILED} means every attempt failed technically; and
 * {@link Outcome#BATCH_ABORTED_RECOVERY} means a context/browser recovery invalidated the batch.
 */
final class AwaitStep {

    enum Outcome {
        READY,
        PENDING,
        DRAINED,
        BATCH_FAILED,
        BATCH_ABORTED_RECOVERY
    }

    /** A settled tab, read atomically in the same tick it became READY (no separate-read race). */
    static final class ReadyTab {
        final String tabId;
        final String requestedUrl;
        final PlaywrightPageState state;
        final ReadinessLabel readinessLabel;

        ReadyTab(String tabId, String requestedUrl, PlaywrightPageState state, ReadinessLabel readinessLabel) {
            this.tabId = tabId;
            this.requestedUrl = requestedUrl;
            this.state = state;
            this.readinessLabel = readinessLabel;
        }
    }

    private final Outcome outcome;
    private final ReadyTab readyTab;

    private AwaitStep(Outcome outcome, ReadyTab readyTab) {
        this.outcome = outcome;
        this.readyTab = readyTab;
    }

    static AwaitStep ready(ReadyTab tab) {
        return new AwaitStep(Outcome.READY, tab);
    }

    static AwaitStep of(Outcome outcome) {
        return new AwaitStep(outcome, null);
    }

    Outcome getOutcome() {
        return outcome;
    }

    /** The ready tab, only present when {@link #getOutcome()} is {@link Outcome#READY}. */
    ReadyTab getReadyTab() {
        return readyTab;
    }
}
