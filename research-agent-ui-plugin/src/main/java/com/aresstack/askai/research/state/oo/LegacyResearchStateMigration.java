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
     * Remap a memento persisted BEFORE the phases-v4 marker existed. In that world "outline"
     * was the pre-research legacy phase (SUBMIT_SCOPE already led straight to research), so an
     * old outline stand proves NOTHING about Sources being done — loading it as the new
     * phase-3 Outline would skip research. PO decision: pre-v4 outline migrates conservatively
     * to Sources ("research") / waiting; an interruption keeps its interrupt state but
     * continues into that gate; no old outline approval is carried forward as a new phase-3
     * approval (the outline artifact on disk stays untouched). Cancelled stays cancelled.
     * The evidence/review/finalization trio keeps its existing remap at the factory's restore
     * choke point — this method only resolves the id collision the marker disambiguates.
     */
    public static ResearchStateMemento migratePreV4(ResearchStateMemento memento) {
        if (memento == null || !ResearchStateIds.OUTLINE.equals(memento.getPhaseId())) {
            return memento;
        }
        String stateId = memento.getStateId();
        if (ResearchStateIds.CANCELLED.equals(stateId)) {
            return new ResearchStateMemento(ResearchStateIds.RESEARCH, ResearchStateIds.CANCELLED,
                    null, memento.getRevision(), null);
        }
        if (ResearchStateIds.PAUSED.equals(stateId) || ResearchStateIds.BLOCKED.equals(stateId)
                || ResearchStateIds.FAILED.equals(stateId)) {
            return new ResearchStateMemento(ResearchStateIds.RESEARCH, stateId,
                    ResearchStateIds.WAITING, memento.getRevision(), null);
        }
        return new ResearchStateMemento(ResearchStateIds.RESEARCH, ResearchStateIds.WAITING,
                null, memento.getRevision(), null);
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
        // #43: legacy 7-phase vocabulary maps to its canonical 4-phase home before the strict
        // factory sees it (evidence -> sources, review/finalization -> document).
        String canonical = ResearchStateIds.canonicalPhaseId(phaseId);
        return factory.phase(canonical,
                factory.state(canonical, stateId, continuationStateId, approvalId));
    }
}
