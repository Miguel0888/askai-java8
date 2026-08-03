package com.aresstack.askai.research.knowledge.processing;

/**
 * The neutral application port the host's source-acceptance hook points at: "this capture was accepted, make
 * sure it gets processed" (§3). It hides the queue, worker, NLP and embedding entirely, so the Swing/plugin
 * layer holds NO processing logic — only {@code SourceAcceptanceService → scheduler.enqueue(captureId,
 * sourceId)}. A no-op default keeps acceptance decoupled in tests / the clickdummy.
 */
public interface KnowledgeProcessingScheduler {

    KnowledgeProcessingScheduler NONE = new KnowledgeProcessingScheduler() {
        public void enqueue(String captureId, String sourceId) {
        }
    };

    void enqueue(String captureId, String sourceId);
}
