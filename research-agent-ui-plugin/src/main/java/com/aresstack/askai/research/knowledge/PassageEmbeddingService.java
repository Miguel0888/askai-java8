package com.aresstack.askai.research.knowledge;

/**
 * Produces the FINAL per-passage vector (§10) used for semantic search, topic assignment/clustering, centroids
 * and nearest-neighbour lookup. Preferably a fresh embedding over the whole passage text (not the last
 * sentence vector, and preferably not a mere mean of sentence vectors) when the backend supports it.
 */
public interface PassageEmbeddingService {

    PassageVector embedPassage(String passageText);

    EmbeddingMetadata metadata();
}
