package com.aresstack.askai.research.knowledge;

import java.util.List;

/**
 * Neutral BATCH embedding port. Every vector carries its model identity — vectors of different
 * fingerprints or dimensions must NEVER be compared (enforced in {@link VectorMath}). The concrete
 * adapter (central AskAI embedding model / local runtime) follows in its own slice; the pipeline and its
 * tests only need the port.
 */
public interface EmbeddingPort {

    final class EmbeddingVector {
        private final String modelId;
        private final String modelFingerprint;
        private final float[] values;

        public EmbeddingVector(String modelId, String modelFingerprint, float[] values) {
            this.modelId = modelId == null ? "" : modelId;
            this.modelFingerprint = modelFingerprint == null ? "" : modelFingerprint;
            this.values = values == null ? new float[0] : values.clone();
        }

        public String getModelId() {
            return modelId;
        }

        public String getModelFingerprint() {
            return modelFingerprint;
        }

        public int getDimension() {
            return values.length;
        }

        public float[] getValues() {
            return values.clone();
        }

        float[] raw() {
            return values;
        }
    }

    /** One vector per input text, in input order. */
    List<EmbeddingVector> embed(List<String> texts);
}
