package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * K4: the Concept tab's search bar filters the cards by NAME (case-insensitive, path shown) —
 * the "Chats durchsuchen…" idiom on the one scoping work surface.
 */
public class ConceptPaperFilterTest {

    private static final String DOCUMENT = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":["
            + "{\"RTOS-Grundlagen\":[{\"Task Scheduling\":[],\"Task Notifications\":[]}],"
            + "\"Praxis\":[]}]}";

    @Test
    public void matchingCardsAreListedWithTheirFullPath() {
        String markdown = ConceptPaperView.filterMarkdown(DOCUMENT, "task");
        assertTrue(markdown, markdown.contains("2 cards matching \"task\""));
        assertTrue(markdown, markdown.contains("- RTOS-Grundlagen › Task Scheduling"));
        assertTrue(markdown, markdown.contains("- RTOS-Grundlagen › Task Notifications"));
        assertTrue("the way back is explained",
                markdown.contains("empty search field"));
    }

    @Test
    public void aMissAndBrokenJsonStayHonest() {
        assertTrue(ConceptPaperView.filterMarkdown(DOCUMENT, "Zephyr")
                .contains("No card matches \"Zephyr\""));
        assertTrue(ConceptPaperView.filterMarkdown("broken", "x")
                .contains("No card matches"));
    }
}
