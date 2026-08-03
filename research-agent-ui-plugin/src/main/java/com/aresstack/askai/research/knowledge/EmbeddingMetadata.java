package com.aresstack.askai.research.knowledge;

/**
 * Identifies the semantic space a vector lives in. Vectors of different {@code modelFingerprint} or
 * {@code dimension} must NEVER be compared directly (§7). Carried on every embedded sentence, passage and
 * projection so a later embedding-model or pipeline change produces a NEW derivable run rather than silently
 * mixing incompatible vectors.
 */
public final class EmbeddingMetadata {

    private final String modelFingerprint;
    private final int dimension;
    private final String normalization;
    private final String embeddingPipelineVersion;

    public EmbeddingMetadata(String modelFingerprint, int dimension, String normalization,
                             String embeddingPipelineVersion) {
        this.modelFingerprint = modelFingerprint == null ? "" : modelFingerprint;
        this.dimension = dimension;
        this.normalization = normalization == null ? "" : normalization;
        this.embeddingPipelineVersion = embeddingPipelineVersion == null ? "" : embeddingPipelineVersion;
    }

    public String getModelFingerprint() {
        return modelFingerprint;
    }

    public int getDimension() {
        return dimension;
    }

    public String getNormalization() {
        return normalization;
    }

    public String getEmbeddingPipelineVersion() {
        return embeddingPipelineVersion;
    }

    /** True when two vectors carrying these metadata may be compared (same space AND dimension). */
    public boolean isComparableWith(EmbeddingMetadata other) {
        return other != null
                && dimension == other.dimension
                && modelFingerprint.equals(other.modelFingerprint);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmbeddingMetadata)) {
            return false;
        }
        EmbeddingMetadata that = (EmbeddingMetadata) o;
        return dimension == that.dimension
                && modelFingerprint.equals(that.modelFingerprint)
                && normalization.equals(that.normalization)
                && embeddingPipelineVersion.equals(that.embeddingPipelineVersion);
    }

    @Override
    public int hashCode() {
        int result = modelFingerprint.hashCode();
        result = 31 * result + dimension;
        result = 31 * result + normalization.hashCode();
        result = 31 * result + embeddingPipelineVersion.hashCode();
        return result;
    }
}
