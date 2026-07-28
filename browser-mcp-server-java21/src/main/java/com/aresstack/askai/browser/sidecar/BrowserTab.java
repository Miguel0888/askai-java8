package com.aresstack.askai.browser.sidecar;

/**
 * One background tab's mutable bookkeeping inside the {@link TabManager}: its stable id, the URL requested for
 * it, the opaque page handle from the {@link TabPageGateway} (null until navigation starts), its lifecycle
 * {@link TabState}, the per-tab absolute deadline, the readiness memory it carries between probes, and the
 * settled {@link ReadinessLabel} once known. Not thread-safe by design — only the scheduler touches it, always
 * on one thread.
 */
final class BrowserTab {

    final String tabId;
    final String requestedUrl;
    final ReadinessState readiness = new ReadinessState();

    Object handle;
    TabState state = TabState.QUEUED;
    long deadlineMillis = Long.MAX_VALUE;
    ReadinessLabel readinessLabel = ReadinessLabel.PENDING;

    BrowserTab(String tabId, String requestedUrl) {
        this.tabId = tabId;
        this.requestedUrl = requestedUrl;
    }
}
