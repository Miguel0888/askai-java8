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

    public Passage(String passageId, String captureId, List<String> sentenceIds, String headingPath,
                   String text, String embeddingFingerprint) {
        this.passageId = passageId == null ? "" : passageId;
        this.captureId = captureId == null ? "" : captureId;
        this.sentenceIds = sentenceIds == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(sentenceIds));
        this.headingPath = headingPath == null ? "" : headingPath;
        this.text = text == null ? "" : text;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
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
}
