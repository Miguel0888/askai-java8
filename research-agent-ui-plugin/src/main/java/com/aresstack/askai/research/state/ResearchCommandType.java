package com.aresstack.askai.research.state;

/** The commands the research state machine understands. */
public enum ResearchCommandType {
    START,
    SUBMIT_SCOPE,
    PROPOSE_OUTLINE,
    APPROVE_OUTLINE,
    REQUEST_OUTLINE_CHANGES,
    START_RESEARCH,
    REQUEST_EVIDENCE_REVIEW,
    APPROVE_EVIDENCE,
    START_DRAFTING,
    REQUEST_DRAFT_REVIEW,
    REQUEST_REVISION,
    APPROVE_DRAFT,
    REQUEST_FINAL_REVIEW,
    APPROVE_FINAL,
    PAUSE,
    RESUME,
    CANCEL,
    BLOCK,
    UNBLOCK,
    FAIL,
    RETRY
}
