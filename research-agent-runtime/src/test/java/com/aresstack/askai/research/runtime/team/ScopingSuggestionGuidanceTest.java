package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The yellow search tags are the user's ORIENTATION — the live regression was a broad first question
 * ("kleintransporter selbst in wohnmobil umbauen") that yielded a clarifying question and ZERO
 * suggestions, because the prompt told the model zero was "a perfectly normal turn". This pins the
 * corrected guidance: a broad/unclear scope MUST come with direction-opening suggestions, and they
 * accompany the clarifying question instead of being replaced by it.
 */
public class ScopingSuggestionGuidanceTest {

    @Test
    public void aBroadScopeMustProduceOrientationSuggestions() {
        String prompt = TeamAgentPlaybook.scopingSystemPrompt();
        assertTrue("suggestions are framed as orientation", prompt.contains("ORIENTATION MAP"));
        assertTrue("a broad first turn always offers direction-opening suggestions",
                prompt.contains("ALWAYS offer"));
        assertTrue("suggestions accompany the clarifying question",
                prompt.contains("ACCOMPANY your clarifying question"));
        assertTrue("breadth must never suppress suggestions",
                prompt.contains("NEVER withhold suggestions"));
        assertFalse("the old zero-is-normal default is gone",
                prompt.contains("Zero suggestions is a perfectly normal turn"));
    }

    @Test
    public void fillerQueriesStayForbidden() {
        String prompt = TeamAgentPlaybook.scopingSystemPrompt();
        assertTrue("no invented filler queries", prompt.contains("never invent filler queries"));
        assertTrue("queries stay short sub-aspect searches, not the whole question",
                prompt.contains("do not just copy the whole question"));
    }
}
