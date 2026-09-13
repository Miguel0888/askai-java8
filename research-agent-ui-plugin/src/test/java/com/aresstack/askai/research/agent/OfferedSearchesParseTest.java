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
