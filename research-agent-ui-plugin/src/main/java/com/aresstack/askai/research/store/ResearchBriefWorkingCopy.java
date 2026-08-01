package com.aresstack.askai.research.store;

/**
 * The MUTABLE working copy of the research brief: the continuously-maintained draft the scoping assistant
 * edits during the phase (RA-P6 §2). It is NOT a historised revision — it is transient scratch that only
 * becomes fachliche truth when the user approves it. A working copy exists only once the content has actually
 * diverged from the latest approved revision; navigating back into the phase without a change creates none.
 */
public final class ResearchBriefWorkingCopy {

    private final String content;
    private final String contentHash;
    private final int baseApprovedRevision;
    private final long updatedAtMillis;

    public ResearchBriefWorkingCopy(String content, String contentHash, int baseApprovedRevision,
                                    long updatedAtMillis) {
        this.content = content == null ? "" : content;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.baseApprovedRevision = baseApprovedRevision;
        this.updatedAtMillis = updatedAtMillis;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    /** The approved revision this working copy was based on, or 0 when nothing has been approved yet. */
    public int getBaseApprovedRevision() {
        return baseApprovedRevision;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }
}
