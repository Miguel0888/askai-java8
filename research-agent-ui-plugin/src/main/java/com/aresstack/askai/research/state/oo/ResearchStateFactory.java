package com.aresstack.askai.research.state.oo;

/**
 * Builds phase/state objects and rebuilds them from a {@link ResearchStateMemento}. It is the one place that
 * decides whether a (phase, state) combination is legal: impossible combinations are rejected here, so they can
 * never exist as objects. Stateless and shareable.
 */
public final class ResearchStateFactory {

    private static final ResearchStateFactory INSTANCE = new ResearchStateFactory();

    public static ResearchStateFactory getInstance() {
        return INSTANCE;
    }

    /** The starting phase state: SCOPING / new. */
    public ResearchPhaseState initialPhase() {
        return phase(ResearchStateIds.SCOPING, state(ResearchStateIds.SCOPING, ResearchStateIds.NEW, null, null));
    }

    public ResearchPhaseState phase(String phaseId, PhaseState state) {
        return new ResearchStates.Phase(phaseId, state);
    }

    /**
     * Build a {@link PhaseState} for a (phase, state) pair. Interruptions carry a continuation state id;
     * approval gates may carry a pending approval id. Illegal combinations throw {@link IllegalArgumentException}.
     */
    public PhaseState state(String phaseId, String stateId, String continuationStateId,
                            String pendingApprovalId) {
        requireKnownPhase(phaseId);
        if (stateId == null) {
            throw new IllegalArgumentException("stateId must not be null");
        }
        if (ResearchStateIds.NEW.equals(stateId)) {
            requireCombo(phaseId, stateId);
            requireNoApprovalId(stateId, pendingApprovalId);
            return new ResearchStates.NewState(phaseId);
        }
        if (ResearchStateIds.RUNNING.equals(stateId)) {
            requireCombo(phaseId, stateId);
            requireNoApprovalId(stateId, pendingApprovalId);
            return new ResearchStates.RunningState(phaseId);
        }
        if (ResearchStateIds.WAITING.equals(stateId)) {
            requireCombo(phaseId, stateId);
            requireNoApprovalId(stateId, pendingApprovalId);
            return new ResearchStates.WaitingState(phaseId);
        }
        if (ResearchStateIds.WAITING_APPROVAL.equals(stateId)) {
            requireCombo(phaseId, stateId);
            return new ResearchStates.WaitingApprovalState(phaseId, pendingApprovalId);
        }
        if (ResearchStateIds.PAUSED.equals(stateId)) {
            String continuation = requireContinuation(phaseId, continuationStateId);
            return new ResearchStates.PausedState(phaseId, continuation,
                    validInterruptApproval(continuation, pendingApprovalId));
        }
        if (ResearchStateIds.BLOCKED.equals(stateId)) {
            String continuation = requireContinuation(phaseId, continuationStateId);
            return new ResearchStates.BlockedState(phaseId, continuation,
                    validInterruptApproval(continuation, pendingApprovalId));
        }
        if (ResearchStateIds.FAILED.equals(stateId)) {
            String continuation = requireContinuation(phaseId, continuationStateId);
            return new ResearchStates.FailedState(phaseId, continuation,
                    validInterruptApproval(continuation, pendingApprovalId));
        }
        if (ResearchStateIds.COMPLETED.equals(stateId)) {
            requireCombo(phaseId, stateId);
            requireNoApprovalId(stateId, pendingApprovalId);
            return new ResearchStates.TerminalState(phaseId, ResearchStateIds.COMPLETED);
        }
        if (ResearchStateIds.CANCELLED.equals(stateId)) {
            requireNoApprovalId(stateId, pendingApprovalId);
            return new ResearchStates.TerminalState(phaseId, ResearchStateIds.CANCELLED); // cancel valid anywhere
        }
        throw new IllegalArgumentException("unknown stateId: " + stateId);
    }

    /** Rebuild a phase state from persisted ids. Invalid combinations are rejected, never guessed. */
    public ResearchPhaseState restore(ResearchStateMemento memento) {
        if (memento == null) {
            throw new IllegalArgumentException("memento must not be null");
        }
        PhaseState state = state(memento.getPhaseId(), memento.getStateId(),
                memento.getContinuationStateId(), memento.getPendingApprovalId());
        return phase(memento.getPhaseId(), state);
    }

    /** Snapshot a phase state to a memento (ids only). */
    public ResearchStateMemento snapshot(ResearchPhaseState phase, long revision) {
        PhaseState state = phase.getCurrentState();
        return new ResearchStateMemento(phase.getPhaseId(), state.getStateId(),
                state.getContinuationStateId(), revision, state.getPendingApprovalId());
    }

    /**
     * The continuation a legacy (phase, PAUSED/BLOCKED/FAILED) pair maps to: the phase's working state. Phases
     * with a running state continue into running; approval-only phases (evidence, review) continue into their
     * approval gate. The legacy {@code ResearchSessionState} cannot carry a precise continuation, so this is the
     * safe, always-valid reconstruction.
     */
    public String defaultContinuationStateId(String phaseId) {
        return ResearchStateGraph.isKnownCombo(phaseId, ResearchStateIds.RUNNING)
                ? ResearchStateIds.RUNNING : ResearchStateIds.WAITING_APPROVAL;
    }

    private static void requireKnownPhase(String phaseId) {
        ResearchStateIds.phase(phaseId); // throws for unknown phase id
    }

    private static void requireCombo(String phaseId, String stateId) {
        if (!ResearchStateGraph.isKnownCombo(phaseId, stateId)) {
            throw new IllegalArgumentException("illegal state for phase: " + phaseId + "/" + stateId);
        }
    }

    /** An approval id is only valid on the approval gate itself. */
    private static void requireNoApprovalId(String stateId, String pendingApprovalId) {
        if (pendingApprovalId != null) {
            throw new IllegalArgumentException("pendingApprovalId is not valid for state " + stateId);
        }
    }

    /** An interruption may carry an approval id only when its continuation is the approval gate. */
    private static String validInterruptApproval(String continuationStateId, String pendingApprovalId) {
        if (pendingApprovalId != null
                && !ResearchStateIds.WAITING_APPROVAL.equals(continuationStateId)) {
            throw new IllegalArgumentException(
                    "pendingApprovalId is only valid when the interruption continues into an approval gate");
        }
        return pendingApprovalId;
    }

    private static String requireContinuation(String phaseId, String continuationStateId) {
        if (continuationStateId == null) {
            throw new IllegalArgumentException("interruption requires a continuation state id");
        }
        // The continuation must be a legal base state of this phase.
        if (!ResearchStateGraph.isKnownCombo(phaseId, continuationStateId)) {
            throw new IllegalArgumentException("illegal continuation for phase: "
                    + phaseId + "/" + continuationStateId);
        }
        return continuationStateId;
    }
}
