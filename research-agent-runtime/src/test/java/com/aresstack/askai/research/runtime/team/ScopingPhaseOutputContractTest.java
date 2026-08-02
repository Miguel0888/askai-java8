package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RA-P6.5: a substantive scoping turn must HELP first — brief + exploration map + at least one search
 * suggestion. The reported GUI failure (an interview-only reply that just asks which subtopic the user
 * means, with no map and no suggestion) must no longer count as a valid first scoping turn.
 */
public class ScopingPhaseOutputContractTest {

    private final PhaseOutputContract contract = new ScopingPhaseOutputContract();

    @Test
    public void aHelpfulFirstTurnWithBriefMapAndSuggestionIsValid() {
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"You want to explore wearables.\","
                        + "\"researchBriefMarkdown\":\"# Research Brief\\n\\nWearables?\","
                        + "\"explorationMap\":{\"root\":\"Wearables\",\"children\":[{\"label\":\"Audio\"}]},"
                        + "\"searchSuggestions\":[{\"query\":\"wearables 2026\",\"priority\":1}]}");
        assertTrue(result.getError(), result.isOk());
        assertTrue(result.getOutput() instanceof ScopingAssistantOutput);
    }

    @Test
    public void anInterviewOnlyReplyThatAsksToNarrowTheTopicIsRejected() {
        // The exact screenshot signature: broad topic -> "which subtopic do you mean?" with no support work.
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"Wearables is a very broad topic! Are you interested in health, "
                        + "audio, or video?\",\"researchBriefMarkdown\":\"# Research Brief\\n\\nWearables\"}");
        assertFalse("brief-only interview reply is not a valid scoping turn", result.isOk());
    }

    @Test
    public void aTurnMissingTheExplorationMapIsRejected() {
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"Ok.\",\"researchBriefMarkdown\":\"# Brief\\nX\","
                        + "\"searchSuggestions\":[{\"query\":\"x\",\"priority\":1}]}");
        assertFalse(result.isOk());
        assertTrue(result.getError().contains("exploration map"));
    }

    @Test
    public void aTurnMissingSearchSuggestionsIsRejected() {
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"Ok.\",\"researchBriefMarkdown\":\"# Brief\\nX\","
                        + "\"explorationMap\":{\"root\":\"X\"}}");
        assertFalse(result.isOk());
        assertTrue(result.getError().contains("search suggestion"));
    }
}
