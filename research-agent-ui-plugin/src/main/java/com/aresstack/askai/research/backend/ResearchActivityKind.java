package com.aresstack.askai.research.backend;

/** The activity sub-kind for an {@code ACTIVITY} backend event (maps onto the host conversation surface). */
public enum ResearchActivityKind {
    THINKING_STARTED,
    THINKING_UPDATE,
    THINKING_FINISHED,
    TOOL_STARTED,
    TOOL_UPDATE,
    TOOL_COMPLETED,
    TOOL_FAILED,
    APPROVAL_REQUIRED
}
