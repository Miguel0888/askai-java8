package com.aresstack.askai.research.state.oo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #43 slice 3 — persisted 7-phase sessions have a DEFINED migration path: the restore choke
 * point remaps evidence → Sources ("research") and review/finalization → Document ("draft"),
 * keeps state/continuation/approval ids, and the next snapshot persists the canonical ids
 * (the memento migrates itself on the first save). The old order is NEVER conserved — the
 * canonical flow stays Concept → Sources → Outline → Document.
 */
public class LegacyPhaseMigrationTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

    @Test
    public void anEvidenceApprovalMementoBecomesTheSourcesGate() {
        ResearchPhaseState restored = factory.restore(new ResearchStateMemento(
                ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL, null, 7L, "ap-1"));
        assertEquals(ResearchStateIds.RESEARCH, restored.getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, restored.getCurrentState().getStateId());
        assertEquals("the SAME approval survives the migration", "ap-1",
                restored.getCurrentState().getPendingApprovalId());
        // The next snapshot persists the canonical id — the memento migrates on first save.
        assertEquals(ResearchStateIds.RESEARCH, factory.snapshot(restored, 8L).getPhaseId());
    }

    @Test
    public void anInterruptedReviewMementoBecomesAnInterruptedDocument() {
        ResearchPhaseState restored = factory.restore(new ResearchStateMemento(
                ResearchStateIds.REVIEW, ResearchStateIds.FAILED,
                ResearchStateIds.WAITING_APPROVAL, 9L, "ap-2"));
        assertEquals(ResearchStateIds.DRAFT, restored.getPhaseId());
        assertEquals(ResearchStateIds.FAILED, restored.getCurrentState().getStateId());
        assertEquals("the exact continuation survives", ResearchStateIds.WAITING_APPROVAL,
                restored.getCurrentState().getContinuationStateId());
        assertEquals("ap-2", restored.getCurrentState().getPendingApprovalId());
    }

    @Test
    public void aFinalizedSessionStaysTerminalInsideDocument() {
        ResearchPhaseState restored = factory.restore(new ResearchStateMemento(
                ResearchStateIds.FINALIZATION, ResearchStateIds.COMPLETED, null, 12L, null));
        assertEquals(ResearchStateIds.DRAFT, restored.getPhaseId());
        assertTrue(restored.getCurrentState().isTerminal());
    }

    @Test
    public void aRunningFinalizationBecomesARunningDocument() {
        ResearchPhaseState restored = factory.restore(new ResearchStateMemento(
                ResearchStateIds.FINALIZATION, ResearchStateIds.RUNNING, null, 3L, null));
        assertEquals(ResearchStateIds.DRAFT, restored.getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, restored.getCurrentState().getStateId());
    }

    @Test
    public void theLegacyPairAdapterAcceptsOldVocabularyThroughTheSameRemap() {
        LegacyResearchStateMigration migration = new LegacyResearchStateMigration(factory,
                new LegacyResearchStateMigration.IdGenerator() {
                    public String newId() {
                        return "synth-1";
                    }
                });
        ResearchPhaseState reconstructed = migration.reconstruct(
                ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL, null, null);
        assertEquals(ResearchStateIds.RESEARCH, reconstructed.getPhaseId());
        assertEquals("a pre-approval-id memento gets its synthesized id", "synth-1",
                reconstructed.getCurrentState().getPendingApprovalId());
    }
}
