package com.aresstack.askai.research.store;

/** The outcome kind of approving the research brief. */
public enum BriefApprovalStatus {

    /** The working copy differed from the latest approved revision, so a new immutable revision was created. */
    APPROVED,
    /** Nothing had changed since the latest approved revision, so no duplicate revision was created. */
    ALREADY_CURRENT
}
