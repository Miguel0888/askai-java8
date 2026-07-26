package com.aresstack.askai.research.domain;

/**
 * An immutable outline node identified by a stable {@code id} (never its list position). {@code parentId} is
 * empty for a top-level section. {@code order} is the position among siblings.
 */
public final class ResearchSection {

    private final String id;
    private final String parentId;
    private final String title;
    private final int order;
    private final ResearchSectionStatus status;
    private final int sourceCount;
    private final int findingCount;
    private final int warningCount;
    private final long revision;

    public ResearchSection(String id, String parentId, String title, int order, ResearchSectionStatus status,
                           int sourceCount, int findingCount, int warningCount, long revision) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("section id must not be empty");
        }
        this.id = id;
        this.parentId = parentId == null ? "" : parentId;
        this.title = title == null ? "" : title;
        this.order = order;
        this.status = status == null ? ResearchSectionStatus.NOT_STARTED : status;
        this.sourceCount = sourceCount;
        this.findingCount = findingCount;
        this.warningCount = warningCount;
        this.revision = revision;
    }

    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
    }

    public boolean isTopLevel() {
        return parentId.isEmpty();
    }

    public String getTitle() {
        return title;
    }

    public int getOrder() {
        return order;
    }

    public ResearchSectionStatus getStatus() {
        return status;
    }

    public int getSourceCount() {
        return sourceCount;
    }

    public int getFindingCount() {
        return findingCount;
    }

    public int getWarningCount() {
        return warningCount;
    }

    public long getRevision() {
        return revision;
    }

    public ResearchSection withTitle(String newTitle, long newRevision) {
        return new ResearchSection(id, parentId, newTitle, order, status, sourceCount, findingCount,
                warningCount, newRevision);
    }

    public ResearchSection withOrder(int newOrder, long newRevision) {
        return new ResearchSection(id, parentId, title, newOrder, status, sourceCount, findingCount,
                warningCount, newRevision);
    }

    public ResearchSection withStatus(ResearchSectionStatus newStatus, long newRevision) {
        return new ResearchSection(id, parentId, title, order, newStatus, sourceCount, findingCount,
                warningCount, newRevision);
    }
}
