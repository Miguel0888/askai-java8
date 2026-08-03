package com.aresstack.askai.research.knowledge.processing.index;

/**
 * A keyword/metadata query, scoped to ONE semantic namespace ({@code embeddingFingerprint}) so only the active
 * embedding world's passages are searched. The text is matched by the text index (Lucene); vectors are not used.
 */
public final class PassageTextQuery {

    private final String embeddingFingerprint;
    private final String text;
    private final int maxResults;

    public PassageTextQuery(String embeddingFingerprint, String text, int maxResults) {
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("embeddingFingerprint (the namespace) must not be empty");
        }
        this.embeddingFingerprint = embeddingFingerprint;
        this.text = text == null ? "" : text;
        this.maxResults = maxResults <= 0 ? 10 : maxResults;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public String getText() {
        return text;
    }

    public int getMaxResults() {
        return maxResults;
    }
}
