package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Gate 8b: offer_searches is the host's side of the search-offer command — lenient about broken
 * entries (dropped, never fatal), bounded, and query-first (a tag without a query is nothing).
 */
public class OfferedSearchesParseTest {

    @Test
    public void queriesAndPurposesParseInOrderWithPriorities() {
        List<ScopingAssistantUpdate.Suggestion> suggestions =
                ResearchAgentSession.parseOfferedSearches(
                        "[{\"query\":\"FreeRTOS ESP32 Grundlagen\",\"purpose\":\"Einstieg\"},"
                                + "{\"query\":\"ESP32 Arduino Praxis\"}]");
        assertEquals(2, suggestions.size());
        assertEquals("FreeRTOS ESP32 Grundlagen", suggestions.get(0).getQuery());
        assertEquals("Einstieg", suggestions.get(0).getPurpose());
        assertEquals(1, suggestions.get(0).getPriority());
        assertEquals(2, suggestions.get(1).getPriority());
    }

    /**
     * Gate 9 lifetime rule: the turn's CLOSING projection carries an (intentionally) empty
     * in-band suggestions list — it must never wipe the tags offer_searches just set. A real,
     * non-empty in-band list still replaces them.
     */
    @Test
    public void anEmptyClosingProjectionKeepsTheOfferedTags() {
        ScopingAssistantUpdate offered = new ScopingAssistantUpdate("scoping",
                java.util.Arrays.asList(new ScopingAssistantUpdate.Suggestion("q1", "p1", 1)),
                "", "");
        ScopingAssistantUpdate closingEmpty = new ScopingAssistantUpdate("scoping",
                java.util.Collections.<ScopingAssistantUpdate.Suggestion>emptyList(),
                "STAY", "advice");

        ScopingAssistantUpdate merged =
                ResearchAgentSession.mergedProjection(closingEmpty, offered, true);
        assertEquals("the offered tags survive the empty closing projection",
                "q1", merged.getSearchSuggestions().get(0).getQuery());
        assertEquals("the projection's other fields still update",
                "STAY", merged.getAdviceRecommendation());

        ScopingAssistantUpdate inBand = new ScopingAssistantUpdate("scoping",
                java.util.Arrays.asList(new ScopingAssistantUpdate.Suggestion("q2", "", 1)),
                "", "");
        assertEquals("a real in-band list replaces the offer", "q2",
                ResearchAgentSession.mergedProjection(inBand, offered, true)
                        .getSearchSuggestions().get(0).getQuery());
        assertEquals("without an offer the empty projection passes through untouched",
                0, ResearchAgentSession.mergedProjection(closingEmpty, offered, false)
                        .getSearchSuggestions().size());
    }

    @Test
    public void brokenEntriesAreDroppedAndTheListIsBounded() {
        assertEquals("blank query dropped", 1, ResearchAgentSession.parseOfferedSearches(
                "[{\"query\":\"  \"},{\"query\":\"ok\"},\"garbage\"]").size());
        assertEquals(0, ResearchAgentSession.parseOfferedSearches("not json").size());
        assertEquals(0, ResearchAgentSession.parseOfferedSearches(null).size());
        StringBuilder many = new StringBuilder("[");
        for (int index = 0; index < 9; index++) {
            many.append(index > 0 ? "," : "").append("{\"query\":\"q").append(index).append("\"}");
        }
        assertTrue("bounded to the readable tag row",
                ResearchAgentSession.parseOfferedSearches(many.append(']').toString())
                        .size() <= 5);
    }
}
