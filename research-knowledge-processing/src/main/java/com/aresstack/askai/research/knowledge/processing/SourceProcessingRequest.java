package com.aresstack.askai.research.knowledge.processing;

/**
 * The request to process one accepted capture into passages (§4). Its {@link #idempotencyKey()} — capture id +
 * segmentation pipeline version + embedding model fingerprint (§4.3) — decides whether this exact processing
 * already exists (then the job completes without recomputation) or whether a pipeline/model change warrants a
 * fresh derivable run.
 */
public final class SourceProcessingRequest {

    private final String captureId;
    private final String sourceId;
    private final String segmentationPipelineVersion;
    private final String embeddingModelFingerprint;

    public SourceProcessingRequest(String captureId, String sourceId, String segmentationPipelineVersion,
                                   String embeddingModelFingerprint) {
        if (captureId == null || captureId.trim().isEmpty()) {
            throw new IllegalArgumentException("captureId must not be empty");
        }
        this.captureId = captureId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.segmentationPipelineVersion = segmentationPipelineVersion == null
                ? "" : segmentationPipelineVersion;
        this.embeddingModelFingerprint = embeddingModelFingerprint == null ? "" : embeddingModelFingerprint;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSegmentationPipelineVersion() {
        return segmentationPipelineVersion;
    }

    public String getEmbeddingModelFingerprint() {
        return embeddingModelFingerprint;
    }

    /** The idempotency identity: the same key means the same fachliche processing (§4.3). */
    public String idempotencyKey() {
        return captureId + "|" + segmentationPipelineVersion + "|" + embeddingModelFingerprint;
    }
}
