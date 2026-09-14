package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.agent.ResearchStateSnapshot;
import com.aresstack.askai.research.state.ResearchCommandType;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * #43 slice 1 — CHARACTERIZATION of the product phase model as it stands BEFORE the 4-phase
 * migration: seven functional phases in the legacy order (OUTLINE before RESEARCH), the full
 * forward walk, and the snapshot's phase order. The next slice deliberately REWRITES these
 * pins to the ratified model {@code Concept → Sources → Outline → Document}; the diff of this
 * file then documents exactly the semantic change.
 */
public class ProductPhaseModelTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

    @Test
    public void theSnapshotPhaseOrderIsTheProductModel() {
        ResearchStateSnapshot snapshot = ResearchStateSnapshot.of(
                factory.phase(ResearchStateIds.SCOPING,
                        factory.state(ResearchStateIds.SCOPING, ResearchStateIds.NEW,
                                null, null)), 0L, null);
        assertEquals(Arrays.asList(
                        ResearchStateIds.SCOPING, ResearchStateIds.OUTLINE,
                        ResearchStateIds.RESEARCH, ResearchStateIds.EVIDENCE,
                        ResearchStateIds.DRAFT, ResearchStateIds.REVIEW,
                        ResearchStateIds.FINALIZATION),
                snapshot.getPhaseOrder());
    }

    @Test
    public void theForwardWalkCoversTheWholeLifecycle() {
        // SCOPING new → running → (submit) RESEARCH waiting → running → (evidence review)
        // EVIDENCE approval → (approve) DRAFT waiting → running → (draft review) REVIEW
        // approval → (approve) FINALIZATION running → approval → completed.
        assertEdge(ResearchStateIds.SCOPING, ResearchStateIds.NEW,
                ResearchCommandType.START, ResearchStateIds.SCOPING, ResearchStateIds.RUNNING);
        assertEdge(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING,
                ResearchCommandType.SUBMIT_SCOPE,
                ResearchStateIds.RESEARCH, ResearchStateIds.WAITING);
        assertEdge(ResearchStateIds.RESEARCH, ResearchStateIds.WAITING,
                ResearchCommandType.START_RESEARCH,
                ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING);
        assertEdge(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING,
                ResearchCommandType.REQUEST_EVIDENCE_REVIEW,
                ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL);
        assertEdge(ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL,
                ResearchCommandType.APPROVE_EVIDENCE,
                ResearchStateIds.DRAFT, ResearchStateIds.WAITING);
        assertEdge(ResearchStateIds.DRAFT, ResearchStateIds.WAITING,
                ResearchCommandType.START_DRAFTING,
                ResearchStateIds.DRAFT, ResearchStateIds.RUNNING);
        assertEdge(ResearchStateIds.DRAFT, ResearchStateIds.RUNNING,
                ResearchCommandType.REQUEST_DRAFT_REVIEW,
                ResearchStateIds.REVIEW, ResearchStateIds.WAITING_APPROVAL);
        assertEdge(ResearchStateIds.REVIEW, ResearchStateIds.WAITING_APPROVAL,
                ResearchCommandType.APPROVE_DRAFT,
                ResearchStateIds.FINALIZATION, ResearchStateIds.RUNNING);
        assertEdge(ResearchStateIds.FINALIZATION, ResearchStateIds.RUNNING,
                ResearchCommandType.REQUEST_FINAL_REVIEW,
                ResearchStateIds.FINALIZATION, ResearchStateIds.WAITING_APPROVAL);
        assertEdge(ResearchStateIds.FINALIZATION, ResearchStateIds.WAITING_APPROVAL,
                ResearchCommandType.APPROVE_FINAL,
                ResearchStateIds.FINALIZATION, ResearchStateIds.COMPLETED);
    }

    private void assertEdge(String phaseId, String stateId, ResearchCommandType command,
                            String targetPhaseId, String targetStateId) {
        ResearchStateGraph.Edge edge = ResearchStateGraph.forward(phaseId, stateId, command);
        assertNotNull(phaseId + "/" + stateId + " --" + command + "--> missing", edge);
        assertEquals(targetPhaseId, edge.targetPhaseId);
        assertEquals(targetStateId, edge.targetStateId);
    }
}
