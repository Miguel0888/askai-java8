package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.CoverageEmphasis;
import com.aresstack.askai.research.domain.scope.OrientationSuggestion;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.ScopePatch;
import com.aresstack.askai.research.domain.scope.ScopePatchOperations;
import com.aresstack.askai.research.domain.scope.ScopingTurnResult;
import com.aresstack.askai.research.domain.scope.UnresolvedScopeIssue;
import com.aresstack.askai.research.store.FileResearchScopeDraftStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The coordinator owns the scope on the host side: it applies what a turn proposes, persists the result and
 * answers what actually changed. The model proposes; this decides and stores.
 */
public class ResearchScopeCoordinatorTest {

    @Rule
    public TemporaryFolder projectDir = new TemporaryFolder();

    private ResearchScopeCoordinator coordinator() {
        return new ResearchScopeCoordinator(new FileResearchScopeDraftStore(projectDir.getRoot()));
    }

    private static ScopingTurnResult turn(String message, ScopePatch patch,
                                          UnresolvedScopeIssue... issues) {
        return new ScopingTurnResult(message, patch, Arrays.asList(issues),
                Collections.<OrientationSuggestion>emptyList());
    }

    @Test
    public void aTurnIsAppliedPersistedAndReportedAsWhatItChanged() {
        ResearchScopeCoordinator coordinator = coordinator();
        assertTrue(coordinator.isUsable());
        assertTrue(coordinator.current().isEmpty());

        ScopeUpdateResult result = coordinator.apply(turn("Verstanden.", new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Wearables im Baugewerbe"),
                ScopePatchOperations.addFacet("worker-safety", "Arbeitssicherheit", ""),
                ScopePatchOperations.addFacet("occupational-health", "Gesundheit", "")))));

        assertTrue(result.isApplied());
        assertEquals(3, result.getChanges().size());
        assertEquals(2, result.getDraft().includedFacets().size());
        assertEquals("the store owns the counter: exactly one revision per turn",
                1L, result.getDraft().getRevision());
        // Persisted, not just in memory: a fresh coordinator sees the same scope.
        ResearchScopeCoordinator restarted = coordinator();
        assertEquals("Wearables im Baugewerbe", restarted.current().getMission());
        assertEquals(1L, restarted.current().getRevision());
    }

    @Test
    public void severalTurnsAccumulateAndARefinementNeverDropsEarlierDecisions() {
        ResearchScopeCoordinator coordinator = coordinator();
        coordinator.apply(turn("...", new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Wearables"),
                ScopePatchOperations.addFacet("ar", "AR-Brillen", "auch interessant"),
                ScopePatchOperations.addFacet("rings", "Smart Rings", "")))));

        ScopeUpdateResult second = coordinator.apply(turn("...", new ScopePatch(Arrays.asList(
                ScopePatchOperations.confirmFacet("ar", ""),
                ScopePatchOperations.setFacetEmphasis("ar", CoverageEmphasis.Importance.LOW,
                        CoverageEmphasis.ResearchDepth.OVERVIEW, CoverageEmphasis.NO_SHARE_HINT)))));

        ResearchScopeDraft draft = second.getDraft();
        assertEquals(2L, draft.getRevision());
        assertEquals(ScopeFacet.Status.CONFIRMED, draft.facet("ar").getStatus());
        assertEquals("auch interessant", draft.facet("ar").getRationale());
        assertEquals(CoverageEmphasis.Importance.LOW, draft.emphasisOf("ar").getImportance());
        assertEquals("Wearables", draft.getMission());
        assertEquals(2, draft.getFacets().size());
    }

    @Test
    public void aTurnWithoutChangesDoesNotProduceANewRevision() {
        ResearchScopeCoordinator coordinator = coordinator();
        coordinator.apply(turn("...", new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Truthahnragout")))));

        ScopeUpdateResult chatOnly = coordinator.apply(
                turn("Erzähl mir mehr, was interessiert dich daran?", ScopePatch.empty()));

        assertEquals(ScopeUpdateResult.Status.UNCHANGED, chatOnly.getStatus());
        assertEquals("a chatty turn must not inflate the scope history",
                1L, chatOnly.getDraft().getRevision());
    }

    @Test
    public void theTurnsOpenIssuesAreRecordedAndRestatingThemChangesNothing() {
        ResearchScopeCoordinator coordinator = coordinator();
        UnresolvedScopeIssue taxonomy = new UnresolvedScopeIssue("taxonomy",
                "Unklar, ob Ragout und Frikassee zwei Richtungen sind", null,
                UnresolvedScopeIssue.Significance.CRITICAL);

        ScopeUpdateResult first = coordinator.apply(turn("Ich bin mir nicht sicher.",
                ScopePatch.empty(), taxonomy));
        assertTrue("an uncertainty alone IS a scope change", first.isApplied());
        assertEquals(1, first.getDraft().getUnresolvedIssues().size());
        assertTrue(first.getDraft().getUnresolvedIssues().get(0).isCritical());

        ScopeUpdateResult repeated = coordinator.apply(turn("Nach wie vor unklar.",
                ScopePatch.empty(), taxonomy));
        assertEquals("restating the same uncertainty is not a new revision",
                ScopeUpdateResult.Status.UNCHANGED, repeated.getStatus());
        assertEquals(1L, repeated.getDraft().getRevision());
    }

    @Test
    public void anUnreadableDraftIsRefusedInsteadOfSilentlyStartingOver() throws Exception {
        // StoreIo is package-private by design; the test writes the damaged file directly.
        File draftFile = new File(projectDir.getRoot(), "scope-draft.json");
        java.nio.file.Files.write(draftFile.toPath(), "{ broken".getBytes("UTF-8"));

        ResearchScopeCoordinator coordinator = coordinator();
        assertFalse(coordinator.isUsable());
        assertTrue(coordinator.unusableReason(), coordinator.unusableReason().contains("CORRUPT"));

        ScopeUpdateResult rejected = coordinator.apply(turn("...", new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("überschreibt nichts")))));
        assertEquals(ScopeUpdateResult.Status.REJECTED, rejected.getStatus());
        assertTrue(rejected.getReason(), rejected.getReason().contains("unusable"));
        assertEquals("the damaged file is left alone for repair", "{ broken",
                new String(java.nio.file.Files.readAllBytes(draftFile.toPath()), "UTF-8"));
    }

    @Test
    public void theCoordinatorNeitherKnowsNorTouchesTheWorkflow() {
        // The whole class surface: read the draft, apply a turn. There is no submit, approve, phase or
        // readiness anywhere — the user owns the state machine.
        for (java.lang.reflect.Method method : ResearchScopeCoordinator.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name, name.contains("submit") || name.contains("approve")
                    || name.contains("phase") || name.contains("ready") || name.contains("transition"));
        }
    }
}
