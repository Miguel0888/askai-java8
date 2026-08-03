package com.aresstack.askai.research.knowledge.processing.index;

/**
 * One passage as handed to the {@link SemanticKnowledgeIndex}: the searchable text + metadata AND its embedding
 * vector. The index is a REBUILDABLE PROJECTION of the canonical knowledge store (passages + persisted vectors),
 * so this document carries everything a hit needs — nothing is looked up back in Lucene or a vector file.
 *
 * <p>{@code sourceId} is resolved from the {@code SourceCapture} by the caller (never parsed out of an id), and
 * {@code embeddingFingerprint} + {@code embedding.length} decide the semantic namespace — vectors of different
 * fingerprints/dimensions are never comparable.</p>
 */
public final class PassageIndexDocument {

    private final String passageId;
    private final String captureId;
    private final String sourceId;
    private final String text;
    private final String headingPath;
    private final String segmentationPipelineVersion;
    private final String embeddingFingerprint;
    private final float[] embedding;

    public PassageIndexDocument(String passageId, String captureId, String sourceId, String text,
                                String headingPath, String segmentationPipelineVersion,
                                String embeddingFingerprint, float[] embedding) {
        if (passageId == null || passageId.trim().isEmpty()) {
            throw new IllegalArgumentException("passageId must not be empty");
        }
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("embeddingFingerprint must not be empty");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }
        this.passageId = passageId;
        this.captureId = captureId == null ? "" : captureId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.text = text == null ? "" : text;
        this.headingPath = headingPath == null ? "" : headingPath;
        this.segmentationPipelineVersion =
                segmentationPipelineVersion == null ? "" : segmentationPipelineVersion;
        this.embeddingFingerprint = embeddingFingerprint;
        this.embedding = embedding.clone();
    }

    public String getPassageId() {
        return passageId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getText() {
        return text;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public String getSegmentationPipelineVersion() {
        return segmentationPipelineVersion;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public int getDimension() {
        return embedding.length;
    }

    public float[] getEmbedding() {
        return embedding.clone();
    }
}
