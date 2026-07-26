package com.aresstack.askai.research.domain;

/**
 * An immutable snapshot of the (whole-document or per-section) rendered Markdown at a given revision,
 * identified by a stable {@code id}. {@code sectionId} is empty for the whole document.
 */
public final class ResearchDocumentRevision {

    private final String id;
    private final long revisionNumber;
    private final String sectionId;
    private final String markdown;

    public ResearchDocumentRevision(String id, long revisionNumber, String sectionId, String markdown) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("document revision id must not be empty");
        }
        this.id = id;
        this.revisionNumber = revisionNumber;
        this.sectionId = sectionId == null ? "" : sectionId;
        this.markdown = markdown == null ? "" : markdown;
    }

    public String getId() {
        return id;
    }

    public long getRevisionNumber() {
        return revisionNumber;
    }

    public String getSectionId() {
        return sectionId;
    }

    public boolean isWholeDocument() {
        return sectionId.isEmpty();
    }

    public String getMarkdown() {
        return markdown;
    }
}
