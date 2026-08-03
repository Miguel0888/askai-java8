package com.aresstack.askai.research.knowledge.processing.index;

/**
 * A semantic (vector) query, scoped to ONE semantic namespace ({@code embeddingFingerprint}). The query vector
 * MUST come from the same embedding world (same fingerprint AND dimension) as the indexed passages — the index
 * rejects a cross-world comparison rather than returning noise. Cosine similarity self-normalizes, so the query
 * vector need not be unit length.
 */
public final class PassageSemanticQuery {

    private final String embeddingFingerprint;
    private final float[] queryVector;
    private final int maxResults;

    public PassageSemanticQuery(String embeddingFingerprint, float[] queryVector, int maxResults) {
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("embeddingFingerprint (the namespace) must not be empty");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        this.embeddingFingerprint = embeddingFingerprint;
        this.queryVector = queryVector.clone();
        this.maxResults = maxResults <= 0 ? 10 : maxResults;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public int getDimension() {
        return queryVector.length;
    }

    public float[] getQueryVector() {
        return queryVector.clone();
    }

    public int getMaxResults() {
        return maxResults;
    }
}
