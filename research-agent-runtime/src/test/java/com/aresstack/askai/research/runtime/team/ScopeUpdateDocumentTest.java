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
        assertTrue(document.getRejections().isEmpty());
    }

    @Test
    public void aTurnWithoutScopeChangesCarriesNoDocumentAtAll() {
        ScopingAssistantOutputParser.Result result = parse("");

        assertTrue(result.getError(), result.isOk());
        assertEquals("no wire line is sent for a purely conversational turn",
                null, result.getOutput().getScopeUpdate());
    }

    @Test
    public void malformedProposalsAreDroppedWithAReasonRatherThanForwarded() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":["
                        + "{\"kind\":\"addFacet\",\"facetId\":\"ok\",\"label\":\"Fein\"},"
                        + "{\"kind\":\"addFacet\",\"facetId\":\"nolabel\"},"
                        + "{\"kind\":\"invent\",\"facetId\":\"x\"}]},"
                        + "\"orientationSuggestions\":[{\"query\":\"only a query\"}]");

        assertTrue(result.getError(), result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertTrue(document.toJson(), document.toJson().contains("\"facetId\":\"ok\""));
        assertFalse(document.toJson(), document.toJson().contains("nolabel"));
        assertFalse("an unknown kind never reaches the host",
                document.toJson().contains("invent"));
        assertFalse("a suggestion without a label would show the query as UI text",
                document.toJson().contains("only a query"));
        assertEquals(3, document.getRejections().size());
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
