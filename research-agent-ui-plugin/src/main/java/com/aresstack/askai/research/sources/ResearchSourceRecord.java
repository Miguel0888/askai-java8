package com.aresstack.askai.research.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable structured research source. Sources are NOT Markdown artifacts: they are records with a stable
 * id and a monotonic revision, managed through {@link ResearchSourceRepository}. Chapter links use stable
 * section ids derived from the outline.
 */
public final class ResearchSourceRecord {

    private final String sourceId;
    private final String title;
    private final String origin;
    private final String url;
    private final String sourceType;
    private final long capturedAt;
    private final String author;
    private final List<String> linkedSectionIds;
    private final String comment;
    private final SourceRelevance relevance;
    private final SourceReliability reliability;
    private final SourceStatus status;
    private final String snapshotReference;
    private final String checksum;
    private final long revision;
    /** The user web-search query that found this source (empty for agent-accepted sources). */
    private final String searchQuery;

    private ResearchSourceRecord(Builder b) {
        this.sourceId = b.sourceId;
        this.title = str(b.title);
        this.origin = str(b.origin);
        this.url = str(b.url);
        this.sourceType = str(b.sourceType);
        this.capturedAt = b.capturedAt;
        this.author = str(b.author);
        this.linkedSectionIds = Collections.unmodifiableList(new ArrayList<String>(b.linkedSectionIds));
        this.comment = str(b.comment);
        this.relevance = b.relevance == null ? SourceRelevance.UNKNOWN : b.relevance;
        this.reliability = b.reliability == null ? SourceReliability.UNKNOWN : b.reliability;
        this.status = b.status == null ? SourceStatus.NEW : b.status;
        this.snapshotReference = str(b.snapshotReference);
        this.checksum = str(b.checksum);
        this.revision = b.revision;
        this.searchQuery = str(b.searchQuery);
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTitle() {
        return title;
    }

    public String getOrigin() {
        return origin;
    }

    public String getUrl() {
        return url;
    }

    public String getSourceType() {
        return sourceType;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public String getAuthor() {
        return author;
    }

    public List<String> getLinkedSectionIds() {
        return linkedSectionIds;
    }

    public String getComment() {
        return comment;
    }

    public SourceRelevance getRelevance() {
        return relevance;
    }

    public SourceReliability getReliability() {
        return reliability;
    }

    public SourceStatus getStatus() {
        return status;
    }

    public String getSnapshotReference() {
        return snapshotReference;
    }

    public String getChecksum() {
        return checksum;
    }

    public long getRevision() {
        return revision;
    }

    /** The user web-search query that found this source, or "" when it was accepted by the agent. */
    public String getSearchQuery() {
        return searchQuery;
    }

    public Builder toBuilder() {
        return new Builder(sourceId)
                .title(title).origin(origin).url(url).sourceType(sourceType).capturedAt(capturedAt)
                .author(author).linkedSectionIds(linkedSectionIds).comment(comment).relevance(relevance)
                .reliability(reliability).status(status).snapshotReference(snapshotReference)
                .checksum(checksum).revision(revision).searchQuery(searchQuery);
    }

    public static Builder builder(String sourceId) {
        return new Builder(sourceId);
    }

    public static final class Builder {
        private final String sourceId;
        private String title;
        private String origin;
        private String url;
        private String sourceType;
        private long capturedAt;
        private String author;
        private List<String> linkedSectionIds = new ArrayList<String>();
        private String comment;
        private SourceRelevance relevance;
        private SourceReliability reliability;
        private SourceStatus status;
        private String snapshotReference;
        private String checksum;
        private long revision;
        private String searchQuery;

        private Builder(String sourceId) {
            if (sourceId == null || sourceId.trim().isEmpty()) {
                throw new IllegalArgumentException("sourceId must not be empty");
            }
            this.sourceId = sourceId;
        }

        public Builder title(String v) { this.title = v; return this; }
        public Builder origin(String v) { this.origin = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder sourceType(String v) { this.sourceType = v; return this; }
        public Builder capturedAt(long v) { this.capturedAt = v; return this; }
        public Builder author(String v) { this.author = v; return this; }

        public Builder linkedSectionIds(List<String> v) {
            this.linkedSectionIds = new ArrayList<String>(v == null ? new ArrayList<String>() : v);
            return this;
        }

        public Builder comment(String v) { this.comment = v; return this; }
        public Builder relevance(SourceRelevance v) { this.relevance = v; return this; }
        public Builder reliability(SourceReliability v) { this.reliability = v; return this; }
        public Builder status(SourceStatus v) { this.status = v; return this; }
        public Builder snapshotReference(String v) { this.snapshotReference = v; return this; }
        public Builder checksum(String v) { this.checksum = v; return this; }
        public Builder revision(long v) { this.revision = v; return this; }
        public Builder searchQuery(String v) { this.searchQuery = v; return this; }

        public ResearchSourceRecord build() {
            return new ResearchSourceRecord(this);
        }
    }
}
