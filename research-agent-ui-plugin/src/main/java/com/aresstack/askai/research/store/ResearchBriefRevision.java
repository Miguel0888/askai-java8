package com.aresstack.askai.research.store;

/**
 * One IMMUTABLE, user-approved snapshot of the research brief. Approved revisions are the phase's canonical
 * fachliche truth (RA-P6 §3/§12): other phases read the latest approved revision, and a revision is never
 * changed once written — a new approval appends a new revision, the old ones stay readable forever.
 */
public final class ResearchBriefRevision {

    private final int revisionNumber;
    private final String content;
    private final String contentHash;
    private final int previousRevision;
    private final long approvedAtMillis;

    public ResearchBriefRevision(int revisionNumber, String content, String contentHash,
                                 int previousRevision, long approvedAtMillis) {
        this.revisionNumber = revisionNumber;
        this.content = content == null ? "" : content;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.previousRevision = previousRevision;
        this.approvedAtMillis = approvedAtMillis;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    /** The revision this one superseded, or 0 for the first approved revision. */
    public int getPreviousRevision() {
        return previousRevision;
    }

    public long getApprovedAtMillis() {
        return approvedAtMillis;
    }
}
