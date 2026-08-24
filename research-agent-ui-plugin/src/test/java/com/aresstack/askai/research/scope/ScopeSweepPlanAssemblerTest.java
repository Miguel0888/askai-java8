package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The single assembly point: revision, request, mission references and anchors all derive from
 * ONE immutable draft snapshot — and vectors describing a DIFFERENT fence are refused by the
 * plan's own validation, so the mixed-snapshot bug cannot be assembled.
 */
public class ScopeSweepPlanAssemblerTest {

    private static ResearchScopeDraft draft() {
        return ResearchScopeDraft.builder()
                .revision(11L)
                .mission("Welche Wearables sind für den Arbeitsschutz relevant?")
                .domains(Arrays.asList("Arbeitsschutz"))
                .contexts(Arrays.asList("Baustelle"))
                .facets(Arrays.asList(
                        new ScopeFacet("f1", "Sensorhelme", ScopeFacet.Status.CONFIRMED, ""),
                        new ScopeFacet("f2", "Consumer-Fitness", ScopeFacet.Status.EXCLUDED, ""),
                        new ScopeFacet("f3", "Drohnenwartung", ScopeFacet.Status.PROVISIONAL, "")))
                .build();
    }

    private static List<AnchorVector> vectorsOf(ResearchScopeDraft draft) {
        List<AnchorVector> vectors = new ArrayList<AnchorVector>();
        for (ScopeAnchor anchor : draft.getAnchors()) {
            vectors.add(new AnchorVector(anchor.getAnchorId(), anchor.getMembership(),
                    new float[]{1, 0, 0}));
        }
        return vectors;
    }

    @Test
    public void everythingDerivesFromTheOneSnapshot() {
        ResearchScopeDraft draft = draft();
        ScopeSweepConfiguration configuration = ScopeSweepConfiguration.defaults();

        ProbeGenerationRequest request = ScopeSweepPlanAssembler.requestOf(draft, configuration);
        assertEquals(draft.getMission(), request.getMission());
        assertEquals("ALL facet labels travel — the provisional hypothesis must be known "
                + "to the generator", 3, request.getKnownFacetLabels().size());
        assertTrue(request.getKnownFacetLabels().contains("Drohnenwartung"));
        assertEquals("the reconciled anchors of THIS draft", draft.getAnchors().size(),
                request.getAnchors().size());
        assertEquals(configuration.targetBroadProbes, request.getTargetCount());

        List<String> references = ScopeSweepPlanAssembler.missionReferenceTexts(draft);
        assertEquals(Arrays.asList(draft.getMission(), "Arbeitsschutz", "Baustelle"), references);

        ScopeSweepService.SweepPlan plan = ScopeSweepPlanAssembler.planOf(
                draft, "nomic@1", vectorsOf(draft), configuration);
        assertEquals(11L, plan.scopeRevision);
        assertEquals("nomic@1", plan.embeddingFingerprint);
    }

    @Test
    public void vectorsOfAnotherFenceCannotBeAssembledIntoThePlan() {
        ResearchScopeDraft draft = draft();
        // The stale index state: one facet was excluded meanwhile → membership differs.
        ResearchScopeDraft olderFence = draft.toBuilder()
                .facets(Arrays.asList(
                        new ScopeFacet("f1", "Sensorhelme", ScopeFacet.Status.CONFIRMED, ""),
                        new ScopeFacet("f2", "Consumer-Fitness", ScopeFacet.Status.CONFIRMED, ""),
                        new ScopeFacet("f3", "Drohnenwartung", ScopeFacet.Status.PROVISIONAL, "")))
                .build();
        try {
            ScopeSweepPlanAssembler.planOf(draft, "nomic@1", vectorsOf(olderFence),
                    ScopeSweepConfiguration.defaults());
            fail("vectors of a different fence must not assemble");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("DIFFERENT fences"));
        }
    }
}
