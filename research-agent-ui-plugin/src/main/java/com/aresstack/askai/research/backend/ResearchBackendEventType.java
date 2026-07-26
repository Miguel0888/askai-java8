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
    COMPLETED
}
