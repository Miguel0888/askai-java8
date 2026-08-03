package com.aresstack.askai.research.knowledge;

/**
 * The pipeline stages whose failure is reported distinctly (§24). A later stage failing must never roll back an
 * already-successful earlier stage: passages/embeddings/clusters stay valid even if labeling or outline
 * projection fails, and processing is resumable from the failed stage.
 */
public enum SourceProcessingStage {
    EXTRACTION,
    SENTENCE_DETECTION,
    EMBEDDING,
    PASSAGE_PERSISTENCE,
    INDEXING,
    TOPIC_PROJECTION,
    TOPIC_LABELING,
    OUTLINE_PROJECTION
}
