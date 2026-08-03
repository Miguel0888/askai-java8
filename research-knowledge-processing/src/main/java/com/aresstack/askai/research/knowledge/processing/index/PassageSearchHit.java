package com.aresstack.askai.research.knowledge.processing.index;

/** One search result: the passage's identity/metadata and the relevance score (higher = better). */
public final class PassageSearchHit {

    private final String passageId;
    private final String captureId;
    private final String sourceId;
    private final String text;
    private final String headingPath;
    private final double score;

    public PassageSearchHit(String passageId, String captureId, String sourceId, String text,
                            String headingPath, double score) {
        this.passageId = passageId == null ? "" : passageId;
        this.captureId = captureId == null ? "" : captureId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.text = text == null ? "" : text;
        this.headingPath = headingPath == null ? "" : headingPath;
        this.score = score;
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

    public double getScore() {
        return score;
    }
}
