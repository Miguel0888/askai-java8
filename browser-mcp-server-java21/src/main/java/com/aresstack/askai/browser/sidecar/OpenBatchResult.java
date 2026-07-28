package com.aresstack.askai.browser.sidecar;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of registering a background batch: the assigned {@code batchId}, which URLs were accepted (kept,
 * deduplicated, within {@code maxBatchUrls}) versus rejected, and the concurrency actually granted after
 * clamping the caller's request to the backend ceiling. The batch only registers here — pages start loading
 * on later {@code pollNextReady} ticks, never during open.
 */
final class OpenBatchResult {

    private final String batchId;
    private final List<String> acceptedUrls;
    private final List<String> rejectedUrls;
    private final int effectiveConcurrency;

    OpenBatchResult(String batchId, List<String> acceptedUrls, List<String> rejectedUrls,
                    int effectiveConcurrency) {
        this.batchId = batchId;
        this.acceptedUrls = Collections.unmodifiableList(acceptedUrls);
        this.rejectedUrls = Collections.unmodifiableList(rejectedUrls);
        this.effectiveConcurrency = effectiveConcurrency;
    }

    String getBatchId() {
        return batchId;
    }

    List<String> getAcceptedUrls() {
        return acceptedUrls;
    }

    List<String> getRejectedUrls() {
        return rejectedUrls;
    }

    int getEffectiveConcurrency() {
        return effectiveConcurrency;
    }
}
