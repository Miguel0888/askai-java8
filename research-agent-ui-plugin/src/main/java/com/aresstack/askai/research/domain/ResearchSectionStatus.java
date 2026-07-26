package com.aresstack.askai.research.domain;

/** Per-section progress status (independent of the session-level phase/run state). */
public enum ResearchSectionStatus {
    NOT_STARTED,
    RESEARCHING,
    EVIDENCE_READY,
    DRAFTING,
    REVIEW_REQUIRED,
    APPROVED,
    BLOCKED
}
