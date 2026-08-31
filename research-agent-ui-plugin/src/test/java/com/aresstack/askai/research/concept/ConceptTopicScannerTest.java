package com.aresstack.askai.research.concept;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The mechanical half of the one-command exclusion: EXACT (case-insensitive, trimmed) card-name
 * matches only — the scanner reports, it never judges similarity and it never deletes.
 */
public class ConceptTopicScannerTest {

    private static final String DOCUMENT = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":["
            + "{\"RTOS-Grundlagen\":[{\"ESP32 und FreeRTOS Setup\":[{\"ESP-IDF\":[]}],"
            + "\"Kernfunktionen\":[]}]}]}";

    @Test
    public void anExactNestedMatchYieldsTheFullCardPath() {
        assertEquals(Arrays.asList("RTOS-Grundlagen", "ESP32 und FreeRTOS Setup", "ESP-IDF"),
                ConceptTopicScanner.findExactPath(DOCUMENT, "ESP-IDF"));
        assertEquals("case-insensitive, the user need not match casing",
                Arrays.asList("RTOS-Grundlagen", "ESP32 und FreeRTOS Setup", "ESP-IDF"),
                ConceptTopicScanner.findExactPath(DOCUMENT, "esp-idf"));
    }

    @Test
    public void collectCardPathsWalksEveryCardInDocumentOrder() {
        java.util.List<java.util.List<String>> paths =
                ConceptTopicScanner.collectCardPaths(DOCUMENT);
        assertEquals(4, paths.size());
        assertEquals(Arrays.asList("RTOS-Grundlagen"), paths.get(0));
        assertEquals(Arrays.asList("RTOS-Grundlagen", "ESP32 und FreeRTOS Setup"), paths.get(1));
        assertEquals(Arrays.asList("RTOS-Grundlagen", "ESP32 und FreeRTOS Setup", "ESP-IDF"),
                paths.get(2));
        assertEquals(Arrays.asList("RTOS-Grundlagen", "Kernfunktionen"), paths.get(3));
        assertEquals("unreadable input yields nothing, never a crash",
                0, ConceptTopicScanner.collectCardPaths("broken").size());
        assertEquals(0, ConceptTopicScanner.collectCardPaths(null).size());
    }

    @Test
    public void noMatchAndBrokenInputStayQuietInsteadOfCrashing() {
        assertNull("similar is NOT equal — semantic matching is a later, separate step",
                ConceptTopicScanner.findExactPath(DOCUMENT, "ESP-IDF Task Notifications"));
        assertNull(ConceptTopicScanner.findExactPath(DOCUMENT, "Arduino"));
        assertNull(ConceptTopicScanner.findExactPath("not json at all", "ESP-IDF"));
        assertNull(ConceptTopicScanner.findExactPath(null, "ESP-IDF"));
        assertNull(ConceptTopicScanner.findExactPath(DOCUMENT, "  "));
    }
}
