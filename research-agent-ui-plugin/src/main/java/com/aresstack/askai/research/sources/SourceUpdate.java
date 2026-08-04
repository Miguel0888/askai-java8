package com.aresstack.askai.research.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The editable fields of a source, submitted as a whole from the detail editor. Immutable identity/provenance
 * fields (sourceId, capturedAt, checksum, snapshotReference) are not part of an update. The repository applies
 * this atomically and bumps the revision. Maps cleanly onto future tools (source_update, source_link_section,
 * source_comment, source_set_status).
 */
public final class SourceUpdate {

    private final String title;
    private final String origin;
    private final String url;
    private final String sourceType;
    private final String author;
    private final List<String> linkedSectionIds;
    private final String comment;
    private final SourceRelevance relevance;
    private final SourceReliability reliability;
    private final SourceStatus status;
    private final boolean userRelevant;

    private SourceUpdate(Builder b) {
        this.title = b.title;
        this.origin = b.origin;
        this.url = b.url;
        this.sourceType = b.sourceType;
        this.author = b.author;
        this.linkedSectionIds = Collections.unmodifiableList(new ArrayList<String>(b.linkedSectionIds));
        this.comment = b.comment;
        this.relevance = b.relevance;
        this.reliability = b.reliability;
        this.status = b.status;
        this.userRelevant = b.userRelevant;
    }

    /** Start from an existing record so the editor changes only what it wants. */
    public static Builder from(ResearchSourceRecord record) {
        return new Builder()
                .title(record.getTitle()).origin(record.getOrigin()).url(record.getUrl())
                .sourceType(record.getSourceType()).author(record.getAuthor())
                .linkedSectionIds(record.getLinkedSectionIds()).comment(record.getComment())
                .relevance(record.getRelevance()).reliability(record.getReliability())
                .status(record.getStatus()).userRelevant(record.isUserRelevant());
    }

    public String getTitle() { return title; }
    public String getOrigin() { return origin; }
    public String getUrl() { return url; }
    public String getSourceType() { return sourceType; }
    public String getAuthor() { return author; }
    public List<String> getLinkedSectionIds() { return linkedSectionIds; }
    public String getComment() { return comment; }
    public SourceRelevance getRelevance() { return relevance; }
    public SourceReliability getReliability() { return reliability; }
    public SourceStatus getStatus() { return status; }
    public boolean isUserRelevant() { return userRelevant; }

    public static final class Builder {
        private String title = "";
        private String origin = "";
        private String url = "";
        private String sourceType = "";
        private String author = "";
        private List<String> linkedSectionIds = new ArrayList<String>();
        private String comment = "";
        private SourceRelevance relevance = SourceRelevance.UNKNOWN;
        private SourceReliability reliability = SourceReliability.UNKNOWN;
        private SourceStatus status = SourceStatus.NEW;
        private boolean userRelevant;

        public Builder title(String v) { this.title = v == null ? "" : v; return this; }
        public Builder origin(String v) { this.origin = v == null ? "" : v; return this; }
        public Builder url(String v) { this.url = v == null ? "" : v; return this; }
        public Builder sourceType(String v) { this.sourceType = v == null ? "" : v; return this; }
        public Builder author(String v) { this.author = v == null ? "" : v; return this; }

        public Builder linkedSectionIds(List<String> v) {
            this.linkedSectionIds = new ArrayList<String>(v == null ? new ArrayList<String>() : v);
            return this;
        }

        public Builder addSection(String sectionId) {
            if (sectionId != null && !sectionId.trim().isEmpty() && !linkedSectionIds.contains(sectionId)) {
                linkedSectionIds.add(sectionId);
            }
            return this;
        }

        public Builder removeSection(String sectionId) {
            linkedSectionIds.remove(sectionId);
            return this;
        }

        public Builder comment(String v) { this.comment = v == null ? "" : v; return this; }
        public Builder relevance(SourceRelevance v) { this.relevance = v == null ? SourceRelevance.UNKNOWN : v; return this; }
        public Builder reliability(SourceReliability v) { this.reliability = v == null ? SourceReliability.UNKNOWN : v; return this; }
        public Builder status(SourceStatus v) { this.status = v == null ? SourceStatus.NEW : v; return this; }
        public Builder userRelevant(boolean v) { this.userRelevant = v; return this; }

        public SourceUpdate build() {
            return new SourceUpdate(this);
        }
    }
}
