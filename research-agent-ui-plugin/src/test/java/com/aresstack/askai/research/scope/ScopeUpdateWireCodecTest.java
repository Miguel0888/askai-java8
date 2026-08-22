package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.CoverageEmphasis;
import com.aresstack.askai.research.domain.scope.ResearchDeliverable;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.ScopingTurnResult;
import com.aresstack.askai.research.domain.scope.UnresolvedScopeIssue;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The process boundary carries NEUTRAL JSON, not the domain's operation objects. Decoding is strict: an
 * unknown operation or a missing required field fails the whole document rather than applying half of it,
 * because a half-applied turn makes the conversation and the stored scope disagree.
 */
public class ScopeUpdateWireCodecTest {

    @Test
    public void aTypicalTurnDecodesIntoOperationsIssuesAndSuggestions() {
        String json = "{\"assistantMessage\":\"Verstanden.\","
                + "\"operations\":["
                + "{\"kind\":\"setMission\",\"mission\":\"Truthahnragout\"},"
                + "{\"kind\":\"addFacet\",\"facetId\":\"tradition\",\"label\":\"Tradition und Herkunft\","
                + "\"rationale\":\"vom Nutzer gefragt\"},"
                + "{\"kind\":\"setFacetEmphasis\",\"facetId\":\"tradition\",\"importance\":\"HIGH\","
                + "\"researchDepth\":\"DEEP\"}],"
                + "\"unresolvedIssues\":[{\"issueId\":\"taxonomy\",\"description\":\"Ragout vs. Frikassee?\","
                + "\"significance\":\"CRITICAL\"}],"
                + "\"orientationSuggestions\":[{\"label\":\"Tradition und Herkunft kurz prüfen\","
                + "\"query\":\"turkey ragout culinary history traditional dish\","
                + "\"rationale\":\"Unklar, ob es ein tradiertes Gericht ist\"}]}";

        ScopeUpdateWireCodec.Result result = ScopeUpdateWireCodec.decode(json);
        assertTrue(result.getError(), result.isOk());
        ScopingTurnResult turn = result.getTurn();

        ResearchScopeDraft draft = turn.getPatch().applyTo(ResearchScopeDraft.empty());
        assertEquals("Truthahnragout", draft.getMission());
        assertEquals(ScopeFacet.Status.PROVISIONAL, draft.facet("tradition").getStatus());
        assertEquals("Tradition und Herkunft", draft.facet("tradition").getLabel());
        assertEquals(CoverageEmphasis.ResearchDepth.DEEP,
                draft.emphasisOf("tradition").getResearchDepth());

        assertEquals(1, turn.getUnresolvedIssues().size());
        assertEquals(UnresolvedScopeIssue.Significance.CRITICAL,
                turn.getUnresolvedIssues().get(0).getSignificance());
        // The label/query split survives the wire: German tag, English engine query.
        assertEquals("Tradition und Herkunft kurz prüfen",
                turn.getOrientationSuggestions().get(0).getLabel());
        assertEquals("turkey ragout culinary history traditional dish",
                turn.getOrientationSuggestions().get(0).getQuery());
    }

    @Test
    public void aSuggestionWithoutALabelIsRefusedRatherThanShowingTheQueryAsUiText() {
        ScopeUpdateWireCodec.Result result = ScopeUpdateWireCodec.decode(
                "{\"orientationSuggestions\":[{\"query\":\"turkey ragout history\"}]}");

        assertFalse(result.isOk());
        assertTrue(result.getError(), result.getError().contains("label"));
    }

    @Test
    public void anUnknownOperationFailsTheWholeDocumentInsteadOfBeingSkipped() {
        ScopeUpdateWireCodec.Result result = ScopeUpdateWireCodec.decode(
                "{\"operations\":[{\"kind\":\"setMission\",\"mission\":\"ok\"},"
                        + "{\"kind\":\"deleteEverything\"}]}");

        assertFalse(result.isOk());
        assertTrue(result.getError(), result.getError().contains("deleteEverything"));
    }

    @Test
    public void aMissingRequiredFieldIsNamedInTheError() {
        ScopeUpdateWireCodec.Result missingLabel = ScopeUpdateWireCodec.decode(
                "{\"operations\":[{\"kind\":\"addFacet\",\"facetId\":\"x\"}]}");
        assertFalse(missingLabel.isOk());
        assertTrue(missingLabel.getError(), missingLabel.getError().contains("label"));

        ScopeUpdateWireCodec.Result notJson = ScopeUpdateWireCodec.decode("nope");
        assertFalse(notJson.isOk());
        assertTrue(notJson.getError(), notJson.getError().contains("not a JSON object"));
    }

    @Test
    public void anUnknownVocabularyValueFallsBackWhileTheOperationStillApplies() {
        // Vocabulary may grow; an operation kind may not. A future importance value must not cost the turn.
        ScopeUpdateWireCodec.Result result = ScopeUpdateWireCodec.decode(
                "{\"operations\":[{\"kind\":\"addFacet\",\"facetId\":\"x\",\"label\":\"X\"},"
                        + "{\"kind\":\"setFacetEmphasis\",\"facetId\":\"x\",\"importance\":\"ENORMOUS\"}]}");

        assertTrue(result.getError(), result.isOk());
        ResearchScopeDraft draft = result.getTurn().getPatch().applyTo(ResearchScopeDraft.empty());
        assertEquals(CoverageEmphasis.Importance.MEDIUM, draft.emphasisOf("x").getImportance());
    }

    @Test
    public void theDeliverableIncludingItsSynthesisContractCrossesTheWire() {
        ScopeUpdateWireCodec.Result result = ScopeUpdateWireCodec.decode(
                "{\"operations\":[{\"kind\":\"setDeliverable\",\"targetLengthMin\":20,"
                        + "\"targetLengthMax\":30,\"lengthUnit\":\"PAGES\",\"categoryFirst\":true,"
                        + "\"contrastRequired\":true}]}");

        assertTrue(result.getError(), result.isOk());
        ResearchDeliverable deliverable = result.getTurn().getPatch()
                .applyTo(ResearchScopeDraft.empty()).getDeliverable();
        assertEquals(20, deliverable.getTargetLengthMin());
        assertEquals(30, deliverable.getTargetLengthMax());
        assertTrue(deliverable.getSynthesisPolicy().isCategoryFirst());
        assertTrue(deliverable.getSynthesisPolicy().isContrastRequired());
    }

    @Test
    public void anEmptyDocumentIsAValidTurnThatChangesNothing() {
        ScopeUpdateWireCodec.Result result = ScopeUpdateWireCodec.decode(
                "{\"assistantMessage\":\"Erzähl mir mehr.\"}");

        assertTrue(result.getError(), result.isOk());
        assertTrue(result.getTurn().getPatch().isEmpty());
        assertEquals("Erzähl mir mehr.", result.getTurn().getAssistantMessage());
    }
}
