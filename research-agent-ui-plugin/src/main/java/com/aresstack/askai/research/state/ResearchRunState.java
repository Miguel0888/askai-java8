package com.aresstack.askai.research.state;

/**
 * The technical run state — orthogonal to {@link ResearchPhase}. Pausing/blocking/failing changes only this
 * dimension and preserves the phase, so resuming/unblocking/retrying returns to the same phase.
 */
public enum ResearchRunState {
    NEW,
    RUNNING,
    WAITING_FOR_USER,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** Terminal states accept no further work commands. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
