package com.aresstack.askai.research.review;

/** Where the project stands relative to its own sources. Derived, never stored. */
public enum PostSearchReviewStatus {

    /** No material at all, or every reviewable source is covered by a successful review. */
    UP_TO_DATE,

    /** There is material the agent has not reviewed yet — the review is offered. */
    PENDING,

    /** A review of the current material is running right now. */
    IN_PROGRESS,

    /** The last review of exactly this material failed or was cancelled — the offer is a RETRY. */
    RETRYABLE;

    /** True when the user should be offered the "review new sources" action. */
    public boolean isOffered() {
        return this == PENDING || this == RETRYABLE;
    }
}
