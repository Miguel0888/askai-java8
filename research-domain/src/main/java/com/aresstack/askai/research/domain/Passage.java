package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * A semantically coherent passage: consecutive sentences of ONE capture, bounded by source structure and
 * semantic topic shifts. Passages are the unit of clustering, evidence and citation. The embedding vector
 * itself is a REBUILDABLE projection — the domain keeps only the model fingerprint that produced it.
 */
public final class Passage {

    private final String passageId;
    private final String captureId;
    private final List<String> sentenceIds;
    private final String headingPath;
    private final String text;
    private final String embeddingFingerprint;
    private final String segmentationPipelineVersion;

    public Passage(String passageId, String captureId, List<String> sentenceIds, String headingPath,
                   String text, String embeddingFingerprint) {
        this(passageId, captureId, sentenceIds, headingPath, text, embeddingFingerprint, "");
    }

    public Passage(String passageId, String captureId, List<String> sentenceIds, String headingPath,
                   String text, String embeddingFingerprint, String segmentationPipelineVersion) {
        this.passageId = passageId == null ? "" : passageId;
        this.captureId = captureId == null ? "" : captureId;
        this.sentenceIds = sentenceIds == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(sentenceIds));
        this.headingPath = headingPath == null ? "" : headingPath;
        this.text = text == null ? "" : text;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
        this.segmentationPipelineVersion =
                segmentationPipelineVersion == null ? "" : segmentationPipelineVersion;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public List<String> getSentenceIds() {
        return sentenceIds;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public String getText() {
        return text;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    /**
     * The segmentation pipeline version that produced this passage. Together with {@link #getCaptureId} and
     * {@link #getEmbeddingFingerprint} it is the passage's DERIVATION IDENTITY — the persistence layer keys the
     * active generation of a capture by exactly these three, never by parsing the passage id string.
     */
    public String getSegmentationPipelineVersion() {
        return segmentationPipelineVersion;
    }
}
