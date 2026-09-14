package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.agent.ResearchStateSnapshot;
import com.aresstack.askai.research.state.ResearchCommandType;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * #43 — the RATIFIED 4-phase product model: {@code Concept → Sources → Outline → Document}
 * (technical compat ids scoping/research/outline/draft). Evidence review, draft review and
 * finalization are ACTIVITIES inside Sources/Document, never phases; the legacy trio
 * evidence/review/finalization is no longer constructible as a product phase.
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
                        ResearchStateIds.SCOPING, ResearchStateIds.RESEARCH,
                        ResearchStateIds.OUTLINE, ResearchStateIds.DRAFT),
                snapshot.getPhaseOrder());
    }

    @Test
    public void theForwardWalkCoversTheWholeLifecycle() {
        // Concept new → running → (submit) Sources waiting → running → (evidence review)
        // Sources approval → (approve) Outline running → approval → (approve) Document
        // waiting → running → (draft review) Document approval → (approve final) completed.
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
                ResearchStateIds.RESEARCH, ResearchStateIds.WAITING_APPROVAL);
        assertEdge(ResearchStateIds.RESEARCH, ResearchStateIds.WAITING_APPROVAL,
                ResearchCommandType.APPROVE_EVIDENCE,
                ResearchStateIds.OUTLINE, ResearchStateIds.RUNNING);
        assertEdge(ResearchStateIds.OUTLINE, ResearchStateIds.RUNNING,
                ResearchCommandType.PROPOSE_OUTLINE,
                ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL);
        assertEdge(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL,
                ResearchCommandType.APPROVE_OUTLINE,
                ResearchStateIds.DRAFT, ResearchStateIds.WAITING);
        assertEdge(ResearchStateIds.DRAFT, ResearchStateIds.WAITING,
                ResearchCommandType.START_DRAFTING,
                ResearchStateIds.DRAFT, ResearchStateIds.RUNNING);
        assertEdge(ResearchStateIds.DRAFT, ResearchStateIds.RUNNING,
                ResearchCommandType.REQUEST_DRAFT_REVIEW,
                ResearchStateIds.DRAFT, ResearchStateIds.WAITING_APPROVAL);
        assertEdge(ResearchStateIds.DRAFT, ResearchStateIds.WAITING_APPROVAL,
                ResearchCommandType.APPROVE_FINAL,
                ResearchStateIds.DRAFT, ResearchStateIds.COMPLETED);
    }

    @Test
    public void theLegacyTrioIsNoProductPhaseAnymore() {
        for (String legacy : new String[] {ResearchStateIds.EVIDENCE, ResearchStateIds.REVIEW,
                ResearchStateIds.FINALIZATION}) {
            try {
                factory.state(legacy, ResearchStateIds.RUNNING, null, null);
                throw new AssertionError(legacy + " must not be constructible as a phase");
            } catch (IllegalArgumentException expected) {
                // the migration remap is the only legal entrance for these ids
            }
            assertEquals("its canonical home is defined",
                    legacy.equals(ResearchStateIds.EVIDENCE)
                            ? ResearchStateIds.RESEARCH : ResearchStateIds.DRAFT,
                    ResearchStateIds.canonicalPhaseId(legacy));
        }
        assertTrue(ResearchStateIds.isCanonicalPhase(ResearchStateIds.SCOPING));
        assertTrue(ResearchStateIds.isCanonicalPhase(ResearchStateIds.DRAFT));
    }

    @Test
    public void oldForwardEdgesOfTheSevenPhaseWorldAreGone() {
        assertNull(ResearchStateGraph.forward(ResearchStateIds.DRAFT,
                ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_DRAFT));
        assertNull(ResearchStateGraph.forward(ResearchStateIds.DRAFT,
                ResearchStateIds.RUNNING, ResearchCommandType.REQUEST_FINAL_REVIEW));
        assertNull(ResearchStateGraph.forward(ResearchStateIds.OUTLINE,
                ResearchStateIds.WAITING_APPROVAL + "x", ResearchCommandType.APPROVE_OUTLINE));
    }

    private void assertEdge(String phaseId, String stateId, ResearchCommandType command,
                            String targetPhaseId, String targetStateId) {
        ResearchStateGraph.Edge edge = ResearchStateGraph.forward(phaseId, stateId, command);
        assertNotNull(phaseId + "/" + stateId + " --" + command + "--> missing", edge);
        assertEquals(targetPhaseId, edge.targetPhaseId);
        assertEquals(targetStateId, edge.targetStateId);
    }
}
