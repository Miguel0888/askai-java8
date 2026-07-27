package com.aresstack.askai.research.state.oo;

/**
 * The only thing persisted for the state model: stable ids, never the state objects themselves. A
 * {@link ResearchStateFactory} rebuilds the object graph from this; invalid combinations are rejected, not
 * guessed.
 */
public final class ResearchStateMemento {

    private final String phaseId;
    private final String stateId;
    private final String continuationStateId; // null unless the state is an interruption
    private final long revision;
    private final String pendingApprovalId;   // null unless the state is an approval gate

    public ResearchStateMemento(String phaseId, String stateId, String continuationStateId,
                                long revision, String pendingApprovalId) {
        this.phaseId = phaseId;
        this.stateId = stateId;
        this.continuationStateId = continuationStateId;
        this.revision = revision;
        this.pendingApprovalId = pendingApprovalId;
    }

    public String getPhaseId() {
        return phaseId;
    }

    public String getStateId() {
        return stateId;
    }

    public String getContinuationStateId() {
        return continuationStateId;
    }

    public long getRevision() {
        return revision;
    }

    public String getPendingApprovalId() {
        return pendingApprovalId;
    }
}
