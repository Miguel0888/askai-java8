package com.aresstack.askai.research.review;

/**
 * What this project knows about its own reviews: how far a review has ever SUCCEEDED, and which material a
 * review last failed on. Immutable; the persisted half of the review state.
 * <p>
 * The offer "Neue Quellen auswerten" used to be a boolean in the session — so it vanished on restart, was
 * consumed the moment a review STARTED, and never came back after a failure. Nothing about a review is a
 * momentary UI fact: what matters is which sources have been reviewed, and that is a property of the
 * project, readable again after any restart.
 */
public final class PostSearchReviewLedger {

    /** A project that has never reviewed anything. */
    public static final PostSearchReviewLedger INITIAL =
            new PostSearchReviewLedger(SourceCorpusRevision.EMPTY, null);

    private final SourceCorpusRevision reviewedThrough;
    /** The material the last review failed or was cancelled on; null when the last review succeeded. */
    private final SourceCorpusRevision failedOn;

    public PostSearchReviewLedger(SourceCorpusRevision reviewedThrough, SourceCorpusRevision failedOn) {
        this.reviewedThrough = reviewedThrough == null ? SourceCorpusRevision.EMPTY : reviewedThrough;
        this.failedOn = failedOn;
    }

    /** A review of {@code target} succeeded: that material is now covered and no failure stands. */
    public PostSearchReviewLedger reviewed(SourceCorpusRevision target) {
        return new PostSearchReviewLedger(target, null);
    }

    /**
     * A review of {@code target} failed or was cancelled. The watermark does NOT move — nothing was
     * reviewed — but the project remembers WHAT failed, so the offer can come back as a retry.
     */
    public PostSearchReviewLedger failed(SourceCorpusRevision target) {
        return new PostSearchReviewLedger(reviewedThrough, target);
    }

    /**
     * The status for the material as it stands right now.
     *
     * @param current the revision derived from the persisted sources
     * @param inProgress the material a review is running on, or null
     */
    public PostSearchReviewStatus statusFor(SourceCorpusRevision current, SourceCorpusRevision inProgress) {
        SourceCorpusRevision material = current == null ? SourceCorpusRevision.EMPTY : current;
        if (inProgress != null) {
            return PostSearchReviewStatus.IN_PROGRESS;
        }
        if (material.isEmpty() || material.equals(reviewedThrough)) {
            return PostSearchReviewStatus.UP_TO_DATE;
        }
        return material.equals(failedOn)
                ? PostSearchReviewStatus.RETRYABLE
                : PostSearchReviewStatus.PENDING;
    }

    public SourceCorpusRevision getReviewedThrough() {
        return reviewedThrough;
    }

    /** @return the material the last review failed on, or {@code null}. */
    public SourceCorpusRevision getFailedOn() {
        return failedOn;
    }
}
