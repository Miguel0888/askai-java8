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
    /** The reranker ran but found no candidate relevant enough to open (typed, NOT NO_RELEVANT_PATHS). */
    NO_SEMANTIC_MATCHES,
    /** The mandatory reranker endpoint could not be reached or answered with an error status. */
    RERANKER_UNAVAILABLE,
    /** The reranker answered but the response violated the strict contract. */
    RERANKER_INVALID_RESPONSE,
    /** The reranker call timed out. */
    RERANKER_TIMEOUT,
    /** The reranker configuration/snapshot was missing or invalid at run time. */
    RERANKER_CONFIGURATION_ERROR,
    USER_CANCELLED,
    APPROVAL_REQUIRED,
    STATE_CHANGED,
    MCP_UNAVAILABLE
}
