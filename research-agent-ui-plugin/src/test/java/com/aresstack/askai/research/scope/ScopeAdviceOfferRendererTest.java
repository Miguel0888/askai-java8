package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceRequest;
import com.aresstack.askai.research.domain.scope.ScopeAdviceSet;
import com.aresstack.askai.research.domain.scope.ScopeDriftGuard;
import com.aresstack.askai.research.domain.scope.ScopeFacet;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The offer renderer: candidate ids stay verbatim (the decision maps back), anchor RELATIONS
 * become the posts' semantic TEXTS in the conversation's language, and drift guards render as
 * explicitly non-selectable reminders.
 */
public class ScopeAdviceOfferRendererTest {

    @Test
    public void rendersEveryReasonWithItsAnchorContext() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .mission("Welche Wearables sind für den Arbeitsschutz relevant?")
                .facets(Arrays.asList(
                        new ScopeFacet("f1", "Schutzhelme mit Sensorik",
                                ScopeFacet.Status.CONFIRMED, ""),
                        new ScopeFacet("f2", "Consumer-Fitness",
                                ScopeFacet.Status.EXCLUDED, ""),
                        new ScopeFacet("f3", "Exoskelett-Hypothese",
                                ScopeFacet.Status.PROVISIONAL, "")))
                .build();
        // Reconciled anchor ids follow the facets: anchor-f1 IN, anchor-f2 OUT, anchor-f3 PROV.
        ScopeAdviceSet advice = new ScopeAdviceSet(draft.getRevision(), "nomic@1",
                Arrays.asList(
                        new ScopeAdviceCandidate("pending-anchor-f3",
                                ScopeAdviceCandidate.Reason.RESOLVE_PENDING,
                                "Exoskelette zur Entlastung", "anchor-f1", "anchor-f2",
                                "anchor-f3", 0.7d, 0.8d, 1, 3),
                        new ScopeAdviceCandidate("boundary-anchor-f1-anchor-f2",
                                ScopeAdviceCandidate.Reason.CLARIFY_BOUNDARY,
                                "Gesundheitsmonitoring", "anchor-f1", "anchor-f2", "",
                                0.66d, 0.7d, 2, 2),
                        new ScopeAdviceCandidate("unexplored-probe-0009",
                                ScopeAdviceCandidate.Reason.CHECK_UNEXPLORED,
                                "Alleinarbeiterschutz", "", "", "", 0.6d, 0.3d, 3, 1)),
                Arrays.asList(new ScopeDriftGuard("private Fitness-Optimierung", "anchor-f2",
                        "OUT fringe", 1)));

        ChoiceRequest request = ScopeAdviceOfferRenderer.render(advice, draft);

        assertEquals(draft.getMission(), request.getMission());
        assertEquals(3, request.getCandidates().size());
        assertEquals("ids stay verbatim so the decision maps back",
                "pending-anchor-f3", request.getCandidates().get(0).getCandidateId());
        assertTrue("the provisional post's TEXT is the context, not its id",
                request.getCandidates().get(0).getContextNote()
                        .contains("Exoskelett-Hypothese"));
        String boundaryContext = request.getCandidates().get(1).getContextNote();
        assertTrue(boundaryContext.contains("Schutzhelme mit Sensorik"));
        assertTrue(boundaryContext.contains("Consumer-Fitness"));
        assertTrue("an island has no post to lean on",
                request.getCandidates().get(2).getContextNote()
                        .contains("noch von keinem Zaunpfosten"));
        assertEquals(1, request.getDriftGuardNotes().size());
        assertTrue("the guard names the exclusion it protects",
                request.getDriftGuardNotes().get(0).contains("Consumer-Fitness"));
        assertTrue(request.getDriftGuardNotes().get(0).contains("bleibt bewusst ausgeschlossen"));
    }
}
