package com.aresstack.askai.research.runtime.loop;

/** Why an autonomous run ended. Carried in the ACP terminal status and the research activity, not just logs. */
public enum ResearchStopReason {
    SUFFICIENT_EVIDENCE,
    TOOL_BUDGET_EXHAUSTED,
    PAGE_BUDGET_EXHAUSTED,
    SOURCE_BUDGET_EXHAUSTED,
    TIME_BUDGET_EXHAUSTED,
    ERROR_BUDGET_EXHAUSTED,
    NO_RELEVANT_PATHS,
    USER_CANCELLED,
    APPROVAL_REQUIRED,
    STATE_CHANGED,
    MCP_UNAVAILABLE
}
