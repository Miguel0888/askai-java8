package com.aresstack.askai.research.state.oo;

/**
 * The one explicit place that repairs <em>legacy</em> state data for the strict native model. The legacy
 * phase/run-state pair (and any pre-approval-id persisted memento) cannot express the approval id an approval
 * gate now requires, so this adapter synthesizes one when reconstructing such a state. It exists precisely so the
 * normal {@link ResearchStateFactory} can stay strict (it rejects an approval gate without an id); no live/native
 * path performs this repair, only migration of old data.
 */
public final class LegacyResearchStateMigration {

    /** Supplies a synthetic approval id when migrating legacy data that predates approval ids. */
    public interface IdGenerator {
        String newId();
    }

    private final ResearchStateFactory factory;
    private final IdGenerator idGenerator;

    public LegacyResearchStateMigration(ResearchStateFactory factory, IdGenerator idGenerator) {
        this.factory = factory;
        this.idGenerator = idGenerator;
    }

    /**
     * Reconstruct a phase state from legacy ids, synthesizing an approval id when the state is an approval gate,
     * or an interruption that continues into one, and none was persisted.
     */
    public ResearchPhaseState reconstruct(String phaseId, String stateId, String continuationStateId,
                                          String pendingApprovalId) {
        String approvalId = pendingApprovalId;
        boolean isApprovalGate = ResearchStateIds.WAITING_APPROVAL.equals(stateId);
        boolean interruptsApproval = ResearchStateIds.WAITING_APPROVAL.equals(continuationStateId)
                && (ResearchStateIds.PAUSED.equals(stateId)
                    || ResearchStateIds.BLOCKED.equals(stateId)
                    || ResearchStateIds.FAILED.equals(stateId));
        if ((isApprovalGate || interruptsApproval)
                && (approvalId == null || approvalId.trim().isEmpty())) {
            approvalId = idGenerator.newId();
        }
        return factory.phase(phaseId, factory.state(phaseId, stateId, continuationStateId, approvalId));
    }
}
