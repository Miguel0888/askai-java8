package com.aresstack.askai.research.knowledge;

/**
 * The acceptance hook (§3): fired AFTER a visited {@code SourceCapture} has been committed as a source, so the
 * knowledge pipeline can enqueue exactly one processing request per accepted capture. It is a reaction to
 * source acceptance that is INDEPENDENT of the source-level Lucene indexing — a Lucene failure must not
 * prevent the enqueue, and neither must an enqueue failure prevent acceptance. Carries the immutable
 * {@code captureId} (never only the source), so a later changed page (a new capture) is processed as its own
 * version (§13). A no-op default keeps acceptance decoupled when no pipeline is wired (tests / clickdummy).
 */
public interface SourceCaptureAcceptedListener {

    SourceCaptureAcceptedListener NONE = new SourceCaptureAcceptedListener() {
        public void onCaptureAccepted(String captureId, String sourceId) {
        }
    };

    void onCaptureAccepted(String captureId, String sourceId);
}
