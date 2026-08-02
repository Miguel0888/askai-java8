package com.aresstack.askai.research.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The research brief as ONE stable artifact ({@code research-brief}) with a mutable working copy and an
 * append-only list of immutable approved revisions (RA-P6 §1/§11). Pure and I/O-free: it decides WHETHER a
 * working-copy update actually changes anything (by normalized content hash) and WHETHER an approval creates a
 * new revision — the file store only persists what changed. The chat is the fine-grained work history; this
 * artifact's history is the coarse, user-approved fachliche history, so a per-turn edit never becomes a
 * revision — only an approval does.
 */
public final class ResearchBriefArtifact {

    /** The single canonical artifact id for the research brief. */
    public static final String ARTIFACT_ID = "research-brief";

    private final String artifactId;
    private final ResearchBriefWorkingCopy workingCopy;
    private final List<ResearchBriefRevision> approvedRevisions;

    public ResearchBriefArtifact(String artifactId, ResearchBriefWorkingCopy workingCopy,
                                 List<ResearchBriefRevision> approvedRevisions) {
        this.artifactId = artifactId == null || artifactId.trim().isEmpty() ? ARTIFACT_ID : artifactId.trim();
        this.workingCopy = workingCopy;
        this.approvedRevisions = approvedRevisions == null
                ? Collections.<ResearchBriefRevision>emptyList()
                : Collections.unmodifiableList(new ArrayList<ResearchBriefRevision>(approvedRevisions));
    }

    /** A brand-new brief artifact: no working copy, no approved revisions. */
    public static ResearchBriefArtifact empty() {
        return new ResearchBriefArtifact(ARTIFACT_ID, null, null);
    }

    public String getArtifactId() {
        return artifactId;
    }

    public boolean hasWorkingCopy() {
        return workingCopy != null;
    }

    public ResearchBriefWorkingCopy getWorkingCopy() {
        return workingCopy;
    }

    public List<ResearchBriefRevision> getApprovedRevisions() {
        return approvedRevisions;
    }

    /** The latest approved revision, or {@code null} when nothing has been approved yet. */
    public ResearchBriefRevision latestApprovedRevision() {
        return approvedRevisions.isEmpty() ? null : approvedRevisions.get(approvedRevisions.size() - 1);
    }

    /** The latest approved revision number, or 0 when nothing has been approved yet. */
    public int latestApprovedRevisionNumber() {
        ResearchBriefRevision latest = latestApprovedRevision();
        return latest == null ? 0 : latest.getRevisionNumber();
    }

    /** What the scoping assistant is currently working with: the working copy, else the latest approved. */
    public String effectiveContent() {
        if (workingCopy != null) {
            return workingCopy.getContent();
        }
        ResearchBriefRevision latest = latestApprovedRevision();
        return latest == null ? "" : latest.getContent();
    }

    /**
     * Fold a new brief markdown into the working copy — but ONLY if it actually differs from the current
     * effective content (by normalized hash). Identical content is a no-op, so a turn that repeats the same
     * brief writes nothing.
     */
    public Update withWorkingCopyUpdatedTo(String markdown, long nowMillis) {
        String normalized = normalize(markdown);
        String newHash = StoreIo.sha256(normalized);
        if (newHash.equals(effectiveContentHash())) {
            return new Update(this, false);
        }
        ResearchBriefWorkingCopy updated = new ResearchBriefWorkingCopy(
                normalized, newHash, latestApprovedRevisionNumber(), nowMillis);
        return new Update(new ResearchBriefArtifact(artifactId, updated, approvedRevisions), true);
    }

    /**
     * Approve the current working copy into a new immutable revision — unless nothing has changed since the
     * latest approved revision, in which case it is {@link BriefApprovalStatus#ALREADY_CURRENT} and no
     * duplicate revision is created. On approval the working copy is consumed (it has become the revision).
     */
    public Approval approve(long nowMillis) {
        String latestHash = latestApprovedRevision() == null
                ? StoreIo.sha256(normalize("")) : latestApprovedRevision().getContentHash();
        if (workingCopy == null || workingCopy.getContentHash().equals(latestHash)) {
            // Nothing pending, or the working copy equals the latest approved: no new revision. Any
            // no-op working copy is consumed so it does not linger.
            ResearchBriefArtifact cleared = workingCopy == null
                    ? this : new ResearchBriefArtifact(artifactId, null, approvedRevisions);
            return new Approval(cleared, BriefApprovalStatus.ALREADY_CURRENT, latestApprovedRevision());
        }
        int newNumber = latestApprovedRevisionNumber() + 1;
        ResearchBriefRevision revision = new ResearchBriefRevision(newNumber, workingCopy.getContent(),
                workingCopy.getContentHash(), latestApprovedRevisionNumber(), nowMillis);
        List<ResearchBriefRevision> next = new ArrayList<ResearchBriefRevision>(approvedRevisions);
        next.add(revision);
        return new Approval(new ResearchBriefArtifact(artifactId, null, next),
                BriefApprovalStatus.APPROVED, revision);
    }

    private String effectiveContentHash() {
        if (workingCopy != null) {
            return workingCopy.getContentHash();
        }
        ResearchBriefRevision latest = latestApprovedRevision();
        return latest == null ? StoreIo.sha256(normalize("")) : latest.getContentHash();
    }

    /** Normalize line endings and trim surrounding blank space so trivial formatting is not a "change". */
    static String normalize(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /** The result of a working-copy update: the (possibly new) artifact and whether anything changed. */
    public static final class Update {
        private final ResearchBriefArtifact artifact;
        private final boolean changed;

        Update(ResearchBriefArtifact artifact, boolean changed) {
            this.artifact = artifact;
            this.changed = changed;
        }

        public ResearchBriefArtifact getArtifact() {
            return artifact;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    /** The result of an approval: the new artifact, the status, and the revision when one was created. */
    public static final class Approval {
        private final ResearchBriefArtifact artifact;
        private final BriefApprovalStatus status;
        private final ResearchBriefRevision revision;

        Approval(ResearchBriefArtifact artifact, BriefApprovalStatus status, ResearchBriefRevision revision) {
            this.artifact = artifact;
            this.status = status;
            this.revision = revision;
        }

        public ResearchBriefArtifact getArtifact() {
            return artifact;
        }

        public BriefApprovalStatus getStatus() {
            return status;
        }

        /** The newly approved revision when {@link #getStatus()} is APPROVED; the latest one otherwise. */
        public ResearchBriefRevision getRevision() {
            return revision;
        }
    }
}
