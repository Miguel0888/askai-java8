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
    /** The IMMUTABLE language snapshot of this job ("en"/"de"), captured at enqueue time. */
    private final String languageCode;

    /** Legacy/language-unaware form: the documented default language "en" (matches legacy queue files). */
    public SourceProcessingRequest(String captureId, String sourceId, String segmentationPipelineVersion,
                                   String embeddingModelFingerprint) {
        this(captureId, sourceId, segmentationPipelineVersion, embeddingModelFingerprint, "en");
    }

    public SourceProcessingRequest(String captureId, String sourceId, String segmentationPipelineVersion,
                                   String embeddingModelFingerprint, String languageCode) {
        if (captureId == null || captureId.trim().isEmpty()) {
            throw new IllegalArgumentException("captureId must not be empty");
        }
        this.captureId = captureId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.segmentationPipelineVersion = segmentationPipelineVersion == null
                ? "" : segmentationPipelineVersion;
        this.embeddingModelFingerprint = embeddingModelFingerprint == null ? "" : embeddingModelFingerprint;
        // Normalized to the supported sentence-model languages; unknown/missing -> the documented default.
        this.languageCode = "de".equalsIgnoreCase(languageCode == null ? "" : languageCode.trim())
                ? "de" : "en";
    }

    /** The job's immutable language snapshot ("en"/"de") - part of the derivation identity. */
    public String getLanguageCode() {
        return languageCode;
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

    /**
     * The idempotency identity: the same key means the same fachliche processing (§4.3). The LANGUAGE is part
     * of it - the same capture under a different sentence-model language is a DIFFERENT derivation.
     */
    public String idempotencyKey() {
        return captureId + "|" + segmentationPipelineVersion + "|" + embeddingModelFingerprint
                + "|" + languageCode;
    }
}
