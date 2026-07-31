package com.aresstack.askai.research.backend;

/** Kinds of backend event delivered to the UI. */
public enum ResearchBackendEventType {
    SESSION_STATE_CHANGED,
    ACTIVITY,
    APPROVAL_REQUESTED,
    OUTLINE_CHANGED,
    SOURCE_ADDED,
    FINDING_ADDED,
    PROBLEM_REPORTED,
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    BLOCKED,
    ERROR,
    COMPLETED,
    /** Structured in-place progress of the current autonomous run (ONE card per run). */
    RUN_PROGRESS,
    /** Structured terminal result of an autonomous run — basis of the user-facing result card. */
    RUN_OUTCOME,
    /** A technical diagnostic line for the run's collapsible technical details — never a chat bubble. */
    RUN_LOG,
    /** The user's manual input is required (or no longer required), e.g. a CAPTCHA in the browser. */
    USER_ATTENTION,
    /**
     * A VALIDATED workflow proposal from the model-backed TeamAgent (title=command, text=question,
     * technicalDetail=newline-joined aspects). The host re-validates it against its own state machine and
     * executes it — the runtime never transitions state.
     */
    SCOPE_PROPOSAL
}
