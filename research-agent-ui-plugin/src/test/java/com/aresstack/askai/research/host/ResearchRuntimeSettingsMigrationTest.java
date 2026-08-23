package com.aresstack.askai.research.host;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The legacy "Search URL" override predates the engine list. A persisted value that merely mirrors a
 * catalog engine's endpoint (the pre-filled Bing template of the early days) silently defeated the
 * whole engine list — order, fallback endpoints, per-engine result pages and delay — and delivered
 * page 1 of one engine forever. Loading drops exactly those; a genuine dev/test world keeps working.
 */
public class ResearchRuntimeSettingsMigrationTest {

    @Test
    public void aLeftoverCatalogEngineOverrideIsDroppedOnLoad() {
        assertEquals("the old pre-filled Bing template dies here",
                "", ResearchRuntimeSettings.migrateLegacySearchUrl(
                        "https://www.bing.com/search?q={query}"));
        assertEquals("", ResearchRuntimeSettings.migrateLegacySearchUrl(
                "https://html.duckduckgo.com/html/?q={query}"));
        assertEquals("whitespace around the leftover changes nothing",
                "", ResearchRuntimeSettings.migrateLegacySearchUrl(
                        "  https://www.bing.com/search?q={query}  "));
    }

    @Test
    public void aGenuineDevWorldOverrideSurvives() {
        assertEquals("http://127.0.0.1:8099/s?q={query}",
                ResearchRuntimeSettings.migrateLegacySearchUrl("http://127.0.0.1:8099/s?q={query}"));
        assertEquals("", ResearchRuntimeSettings.migrateLegacySearchUrl(null));
        assertEquals("", ResearchRuntimeSettings.migrateLegacySearchUrl("   "));
    }
}
