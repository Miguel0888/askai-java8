package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The scoping phase's own structured output contract: parse rules and canonical round-trip. */
public class ScopingAssistantOutputParserTest {

    @Test
    public void aCompleteSnapshotWithMessageBriefMapAndSuggestionIsValid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"You want to explore wearables.\","
                        + "\"researchBriefMarkdown\":\"# Research Brief\\n\\n## Fragestellung\\n\\nWearables?\","
                        + "\"explorationMap\":{\"root\":\"Wearables\",\"children\":[{\"label\":\"Audio\"}]},"
                        + "\"searchSuggestions\":[{\"query\":\"wearables 2026\",\"priority\":1}]}");
        assertTrue(r.getError(), r.isOk());
        assertEquals("You want to explore wearables.", r.getOutput().getAssistantMessage());
        assertTrue(r.getOutput().getResearchBriefMarkdown().contains("Wearables?"));
        assertTrue(r.getOutput().getExplorationMapMermaid().startsWith("mindmap"));
        assertEquals(1, r.getOutput().getSearchSuggestions().size());
        assertEquals(PhaseAdviceRecommendation.NEUTRAL, r.getOutput().getAdvice().getRecommendation());
    }

    @Test
    public void fullOutputWithMapSuggestionsAndAdviceIsValid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Got it.\",\"researchBriefMarkdown\":\"# Brief\\nWearables\","
                        + "\"explorationMap\":{\"root\":\"Wearables\",\"children\":[{\"label\":\"Audio\"}]},"
                        + "\"searchSuggestions\":["
                        + "{\"query\":\"wearables current technology 2026\",\"purpose\":\"tech\",\"priority\":1},"
                        + "{\"query\":\"smart glasses privacy\",\"purpose\":\"privacy\",\"priority\":2}],"
                        + "\"advice\":{\"recommendation\":\"CONTINUE\",\"reason\":\"precise enough\"}}");
        assertTrue(r.getError(), r.isOk());
        assertEquals(2, r.getOutput().getSearchSuggestions().size());
        assertEquals("wearables current technology 2026", r.getOutput().getSearchSuggestions().get(0).getQuery());
        assertEquals(2, r.getOutput().getSearchSuggestions().get(1).getPriority());
        assertTrue(r.getOutput().getExplorationMapMermaid().startsWith("mindmap"));
        assertEquals(PhaseAdviceRecommendation.CONTINUE, r.getOutput().getAdvice().getRecommendation());
        assertEquals("precise enough", r.getOutput().getAdvice().getReason());
    }

    @Test
    public void adviceTokensParseToTheThreeRecommendations() {
        assertEquals(PhaseAdviceRecommendation.STAY, adviceOf("STAY"));
        assertEquals(PhaseAdviceRecommendation.CONTINUE, adviceOf("continue"));
        assertEquals(PhaseAdviceRecommendation.NEUTRAL, adviceOf("NEUTRAL"));
        assertEquals("an unknown token is neutral", PhaseAdviceRecommendation.NEUTRAL, adviceOf("go-for-it"));
    }

    @Test
    public void missingResearchBriefMarkdownIsInvalid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\"}");
        assertFalse(r.isOk());
        assertTrue(r.getError().contains("researchBriefMarkdown"));
    }

    @Test
    public void anEmptyExplorationMapIsRejected() {
        // A complete snapshot is required (RA-P6.5): a brief-only reply that omits map/suggestions (the
        // reported interview signature) is no longer a valid scoping turn.
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Which subtopic?\",\"researchBriefMarkdown\":\"# Brief\\nX\","
                        + "\"searchSuggestions\":[]}");
        assertFalse(r.isOk());
        assertTrue(r.getError(), r.getError().contains("exploration map"));
    }

    @Test
    public void missingSearchSuggestionsAreRejected() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\",\"researchBriefMarkdown\":\"# Brief\\nX\","
                        + "\"explorationMap\":{\"root\":\"X\"}}");
        assertFalse(r.isOk());
        assertTrue(r.getError(), r.getError().contains("search suggestion"));
    }

    @Test
    public void aSearchSuggestionWithABlankQueryIsRejected() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\",\"researchBriefMarkdown\":\"# Brief\\nX\","
                        + "\"explorationMap\":{\"root\":\"X\"},"
                        + "\"searchSuggestions\":[{\"query\":\"   \",\"priority\":1}]}");
        assertFalse(r.isOk());
        assertTrue(r.getError().contains("query"));
    }

    @Test
    public void canonicalCodecRoundTripPreservesEveryScopingField() {
        ScopingAssistantOutput original = new ScopingAssistantOutput(
                "Message with \"quotes\" and \n newline",
                "# Research Brief\n\n## Fragestellung\n\nWearables mit Audio & Video?",
                new ExplorationMap(new ExplorationNode("Wearables", Arrays.asList(
                        new ExplorationNode("Audio", null), new ExplorationNode("Video", null)))),
                Arrays.asList(new SearchSuggestion("wearables audio video", "scope", 1),
                        new SearchSuggestion("smart glasses privacy GDPR", "", 3)),
                new PhaseAdvice(PhaseAdviceRecommendation.STAY, "one open point remains"));

        ScopingAssistantOutputParser.Result reparsed =
                ScopingAssistantOutputParser.parse(original.canonicalJson());

        assertTrue(reparsed.getError(), reparsed.isOk());
        ScopingAssistantOutput back = reparsed.getOutput();
        assertEquals(original.getAssistantMessage(), back.getAssistantMessage());
        assertEquals(original.getResearchBriefMarkdown(), back.getResearchBriefMarkdown());
        assertEquals("Wearables", back.getExplorationMap().getRoot().getLabel());
        assertEquals(2, back.getExplorationMap().getRoot().getChildren().size());
        assertEquals(original.getExplorationMapMermaid(), back.getExplorationMapMermaid());
        assertEquals(2, back.getSearchSuggestions().size());
        assertEquals("wearables audio video", back.getSearchSuggestions().get(0).getQuery());
        assertEquals(3, back.getSearchSuggestions().get(1).getPriority());
        assertEquals(PhaseAdviceRecommendation.STAY, back.getAdvice().getRecommendation());
        assertEquals("one open point remains", back.getAdvice().getReason());
    }

    private static PhaseAdviceRecommendation adviceOf(String token) {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\",\"researchBriefMarkdown\":\"# B\\nX\","
                        + "\"explorationMap\":{\"root\":\"X\"},"
                        + "\"searchSuggestions\":[{\"query\":\"x\",\"priority\":1}],"
                        + "\"advice\":{\"recommendation\":\"" + token + "\"}}");
        assertTrue(r.getError(), r.isOk());
        return r.getOutput().getAdvice().getRecommendation();
    }
}
