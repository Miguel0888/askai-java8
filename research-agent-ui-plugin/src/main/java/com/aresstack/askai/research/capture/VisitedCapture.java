package com.aresstack.askai.research.capture;

/**
 * A visited page: VISITED on creation, CANDIDATE once assessment metadata is attached. Strictly distinct
 * from a ResearchSourceRecord — a capture NEVER appears in source_list(); only source_accept(captureId)
 * promotes it. Immutable; assessment produces a new instance (no separate persistent candidate type).
 */
public final class VisitedCapture {

    public enum Stage { VISITED, CANDIDATE }

    private final String captureId;
    private final String url;
    private final String canonicalUrl;
    private final String title;
    private final String text;          // already-cleaned text from the browser backend (never raw HTML)
    private final String contentHash;   // sha-256 of the cleaned text
    private final long capturedAt;
    private final String relevance;
    private final String sourceType;
    private final String assessmentNote;

    public VisitedCapture(String captureId, String url, String canonicalUrl, String title, String text,
                          String contentHash, long capturedAt,
                          String relevance, String sourceType, String assessmentNote) {
        this.captureId = captureId;
        this.url = url;
        this.canonicalUrl = canonicalUrl;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.contentHash = contentHash;
        this.capturedAt = capturedAt;
        this.relevance = relevance;
        this.sourceType = sourceType;
        this.assessmentNote = assessmentNote;
    }

    public Stage getStage() {
        return relevance == null && sourceType == null && assessmentNote == null
                ? Stage.VISITED : Stage.CANDIDATE;
    }

    public VisitedCapture withAssessment(String relevance, String sourceType, String assessmentNote) {
        return new VisitedCapture(captureId, url, canonicalUrl, title, text, contentHash, capturedAt,
                relevance, sourceType, assessmentNote);
    }

    public String getCaptureId() { return captureId; }
    public String getUrl() { return url; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public String getContentHash() { return contentHash; }
    public long getCapturedAt() { return capturedAt; }
    public String getRelevance() { return relevance; }
    public String getSourceType() { return sourceType; }
    public String getAssessmentNote() { return assessmentNote; }
}
