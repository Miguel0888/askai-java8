package com.aresstack.askai.research.state;

/**
 * The functional research phase — the "where are we in the work" dimension. This is deliberately separate
 * from {@link ResearchRunState}: PAUSED / BLOCKED / FAILED are run states, never phases.
 */
public enum ResearchPhase {
    SCOPING,
    OUTLINE,
    RESEARCH,
    EVIDENCE,
    DRAFT,
    REVIEW,
    FINALIZATION
}
