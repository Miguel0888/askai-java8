package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The runtime only lets WELL-FORMED proposals cross the process boundary: a malformed operation is dropped
 * here, with a reason in the technical trace, instead of travelling on and being refused invisibly later.
 */
public class ScopeUpdateDocumentTest {

    private static ScopingAssistantOutputParser.Result parse(String scopeFields) {
        return ScopingAssistantOutputParser.parse("{\"assistantMessage\":\"ok\","
                + "\"researchBriefMarkdown\":\"# brief\","
                + "\"searchSuggestions\":[{\"query\":\"q\",\"purpose\":\"p\",\"priority\":1}]"
                + (scopeFields.isEmpty() ? "" : "," + scopeFields) + "}");
    }

    @Test
    public void aScopingTurnCarriesItsProposedOperationsAsNeutralJson() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"addFacet\",\"facetId\":\"health\","
                        + "\"label\":\"Gesundheit\",\"rationale\":\"vom Nutzer genannt\"}]}");

        assertTrue(result.getError(), result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        String json = document.toJson();
        assertTrue(json, json.contains("\"kind\":\"addFacet\""));
        assertTrue(json, json.contains("\"facetId\":\"health\""));
        assertTrue(json, json.contains("\"label\":\"Gesundheit\""));
        assertTrue(document.isValid());
    }

    @Test
    public void aTurnWithoutScopeChangesCarriesNoDocumentAtAll() {
        ScopingAssistantOutputParser.Result result = parse("");

        assertTrue(result.getError(), result.isOk());
        assertEquals("no wire line is sent for a purely conversational turn",
                null, result.getOutput().getScopeUpdate());
    }

    /**
     * ONE malformed element invalidates the whole scope update — never a part of it. But the scope block is
     * OPTIONAL, so it must not cost the user their answer: a failed turn is not committed to history, so
     * killing the turn also erased the message the user had just written and the next turn started with
     * "I need a topic first". The turn survives, the update is dropped whole, and the host reports it.
     */
    @Test
    public void oneMalformedElementDropsTheWholeUpdateButKeepsTheConversation() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":["
                        + "{\"kind\":\"addFacet\",\"facetId\":\"health\",\"label\":\"Gesundheit\"},"
                        + "{\"kind\":\"setFacetEmphasis\"}]}");

        assertTrue("the answer itself is usable", result.isOk());
        assertEquals("ok", result.getOutput().getAssistantMessage());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertFalse("the update as a whole is invalid", document.isValid());
        assertTrue(document.describeViolations(),
                document.describeViolations().contains("setFacetEmphasis"));
        assertFalse("nothing of it may be applied — not even the valid operation",
                document.isValid());
    }

    @Test
    public void anUnknownOperationKindOrALabellessSuggestionAlsoInvalidatesTheUpdateOnly() {
        ScopingAssistantOutputParser.Result unknownKind = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"invent\",\"facetId\":\"x\"}]}");
        assertTrue(unknownKind.isOk());
        assertFalse(unknownKind.getOutput().getScopeUpdate().isValid());
        assertTrue(unknownKind.getOutput().getScopeUpdate().describeViolations().contains("invent"));

        ScopingAssistantOutputParser.Result labelless = parse(
                "\"orientationSuggestions\":[{\"query\":\"only a query\"}]");
        assertTrue(labelless.isOk());
        assertFalse(labelless.getOutput().getScopeUpdate().isValid());
        assertTrue(labelless.getOutput().getScopeUpdate().describeViolations().contains("label"));
    }

    @Test
    public void issuesAndSuggestionsKeepTheirFieldsIncludingTheLabelQuerySplit() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"unresolvedIssues\":[{\"issueId\":\"taxonomy\",\"description\":\"Ragout oder Frikassee?\","
                        + "\"significance\":\"CRITICAL\"}],"
                        + "\"orientationSuggestions\":[{\"label\":\"Tradition kurz prüfen\","
                        + "\"query\":\"turkey ragout culinary history\",\"rationale\":\"unklar\"}]");

        String json = result.getOutput().getScopeUpdate().toJson();
        assertTrue(json, json.contains("\"issueId\":\"taxonomy\""));
        assertTrue(json, json.contains("\"significance\":\"CRITICAL\""));
        assertTrue(json, json.contains("\"label\":\"Tradition kurz prüfen\""));
        assertTrue(json, json.contains("\"query\":\"turkey ragout culinary history\""));
    }

    @Test
    public void textWithQuotesAndNewlinesStaysValidJsonOnTheWire() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"setMission\","
                        + "\"mission\":\"Er sagte \\\"Ragout\\\"\\nund meinte Frikassee\"}]}");

        String json = result.getOutput().getScopeUpdate().toJson();
        assertTrue(json, json.contains("\\\"Ragout\\\""));
        assertTrue(json, json.contains("\\n"));
    }
}
