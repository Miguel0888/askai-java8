package com.aresstack.askai.research.knowledge;

import java.util.Arrays;

/**
 * An embedding vector tagged with the space it belongs to. Comparisons (cosine similarity, nearest-neighbour)
 * must first check {@link EmbeddingMetadata#isComparableWith} — vectors of different fingerprints/dimensions
 * are never mixed (§7). The float array is copied defensively so the value is immutable.
 */
public final class PassageVector {

    private final float[] values;
    private final EmbeddingMetadata metadata;

    public PassageVector(float[] values, EmbeddingMetadata metadata) {
        this.values = values == null ? new float[0] : Arrays.copyOf(values, values.length);
        this.metadata = metadata;
    }

    /** A defensive copy of the raw components. */
    public float[] toArray() {
        return Arrays.copyOf(values, values.length);
    }

    public int dimension() {
        return values.length;
    }

    public float get(int i) {
        return values[i];
    }

    public EmbeddingMetadata getMetadata() {
        return metadata;
    }
}
