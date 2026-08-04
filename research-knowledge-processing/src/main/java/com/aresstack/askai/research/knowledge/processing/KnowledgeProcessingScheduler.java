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

    /**
     * As {@link #enqueue(String, String)} but with the AUTHORITATIVE language snapshot at acceptance time
     * ("en"/"de") - persisted on the job so the language world stays unambiguous after a restart. An empty
     * language means "the caller carries no snapshot" (agent path / legacy); the composition root substitutes
     * the session language there. The default ignores the language (legacy implementations).
     */
    default void enqueue(String captureId, String sourceId, String languageCode) {
        enqueue(captureId, sourceId);
    }
}
