package com.aresstack.askai.research.knowledge.processing.live;

/**
 * The neutral trigger port for the live knowledge projection: "the corpus changed — the projection is stale".
 * Fired AFTER a source-processing job COMPLETED (new passages exist) and after a successful source
 * status/relevance change (the ACTIVE corpus composition changed). Never fired per sentence/passage; the
 * consumer debounces bursts into one rebuild. A no-op default keeps producers decoupled in tests.
 */
public interface KnowledgeProjectionInvalidator {

    void knowledgeChanged();

    void sourceRelevanceChanged(String sourceId);

    KnowledgeProjectionInvalidator NONE = new KnowledgeProjectionInvalidator() {
        public void knowledgeChanged() {
        }

        public void sourceRelevanceChanged(String sourceId) {
        }
    };
}
