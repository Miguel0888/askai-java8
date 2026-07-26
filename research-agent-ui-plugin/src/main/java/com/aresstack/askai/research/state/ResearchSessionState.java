package com.aresstack.askai.research.state;

/**
 * Immutable snapshot of a research session's two-dimensional state plus a monotonically increasing
 * revision. Every accepted transition produces a new instance with {@code revision + 1}; rejected
 * transitions reuse the current instance unchanged.
 */
public final class ResearchSessionState {

    private final ResearchPhase phase;
    private final ResearchRunState runState;
    private final long revision;

    public ResearchSessionState(ResearchPhase phase, ResearchRunState runState, long revision) {
        if (phase == null || runState == null) {
            throw new IllegalArgumentException("phase and runState must not be null");
        }
        this.phase = phase;
        this.runState = runState;
        this.revision = revision;
    }

    /** The starting state of a new session: SCOPING / NEW at revision 0. */
    public static ResearchSessionState initial() {
        return new ResearchSessionState(ResearchPhase.SCOPING, ResearchRunState.NEW, 0L);
    }

    public ResearchPhase getPhase() {
        return phase;
    }

    public ResearchRunState getRunState() {
        return runState;
    }

    public long getRevision() {
        return revision;
    }

    /** @return a new state with the given phase/runState and the revision advanced by one. */
    ResearchSessionState advance(ResearchPhase newPhase, ResearchRunState newRunState) {
        return new ResearchSessionState(newPhase, newRunState, revision + 1);
    }

    public boolean isTerminal() {
        return runState.isTerminal();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ResearchSessionState)) {
            return false;
        }
        ResearchSessionState that = (ResearchSessionState) other;
        return revision == that.revision && phase == that.phase && runState == that.runState;
    }

    @Override
    public int hashCode() {
        int result = phase.hashCode();
        result = 31 * result + runState.hashCode();
        result = 31 * result + (int) (revision ^ (revision >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return phase + "/" + runState + "@" + revision;
    }
}
