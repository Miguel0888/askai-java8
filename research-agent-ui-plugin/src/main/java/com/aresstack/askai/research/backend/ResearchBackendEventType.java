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
    SCOPE_PROPOSAL,
    /**
     * The model-backed greeting was delivered successfully. The host advances the scope state one step so the
     * greeting (which depends only on the state) is never repeated on a restart.
     */
    GREETING_DONE,
    /**
     * A display-only scoping assistant PROJECTION (search suggestions + advisory advice) for the scoping
     * workspace. Carries no research brief and no workflow authority; a later one replaces it.
     */
    SCOPING_PROJECTION,
    /**
     * The proposed CHANGES to the scope the host owns (neutral JSON in {@code text}). Unlike the display
     * projection this one is applied to the persisted scope draft — or visibly refused.
     */
    SCOPE_UPDATE,
    /**
     * The research brief markdown (the scoping phase's primary artifact), in {@code text}. The host persists it
     * to the brief working copy (one path) and shows it in the "Fragestellung" view. No approval, no transition.
     */
    RESEARCH_BRIEF,
    /**
     * A user-triggered web search lifecycle event ({@code title} = started|progress|completed|failed,
     * {@code text} = the user-facing line, {@code technicalDetail} = the correlating requestId). Rendered as a
     * transient search activity; it never changes the phase or the state machine.
     */
    MANUAL_SEARCH
}
