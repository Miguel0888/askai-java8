package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The host side of the initial-search strategy seam: the persisted selection round-trips, an API-provider
 * selection publishes EXACTLY the snapshot shape the agent's SearchStrategyConfigurationLoader documents,
 * and the launch environment carries ASKAI_SEARCH_STRATEGY_CONFIG only when a snapshot exists — the legacy
 * browser selection hands over nothing, which IS the agent's documented legacy path.
 */
public class SearchStrategyWiringTest {

    private static final class MemoryStore implements WorkspaceStateStore {
        private final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            try {
                return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }

    @Test
    public void anApiSelectionSnapshotMatchesTheDocumentedAgentContract() {
        SearchStrategySelection selection = new SearchStrategySelection(
                SearchStrategySelection.STRATEGY_API_PROVIDER, "DATA_FOR_SEO", "GOOGLE", "de", "de");
        assertTrue(selection.isApiProvider());
        assertEquals("{ \"strategy\": \"API_PROVIDER\", \"provider\": \"DATA_FOR_SEO\", "
                        + "\"engine\": \"GOOGLE\", \"language\": \"de\", \"country\": \"de\" }",
                selection.toSnapshotJson());
    }

    @Test
    public void anEmptyEngineFallsBackToProviderDefaultAndEmptyLocaleIsOmitted() {
        SearchStrategySelection selection = new SearchStrategySelection(
                SearchStrategySelection.STRATEGY_API_PROVIDER, "BRAVE_SEARCH_API", "", "", "");
        assertEquals("{ \"strategy\": \"API_PROVIDER\", \"provider\": \"BRAVE_SEARCH_API\", "
                + "\"engine\": \"PROVIDER_DEFAULT\" }", selection.toSnapshotJson());
    }

    @Test
    public void theLegacySelectionNeverCountsAsApiProvider() {
        assertFalse(SearchStrategySelection.legacyBrowser().isApiProvider());
        // API strategy without a provider is not a usable API selection either → no snapshot.
        assertFalse(new SearchStrategySelection(
                SearchStrategySelection.STRATEGY_API_PROVIDER, "", "GOOGLE", "de", "de")
                .isApiProvider());
    }

    @Test
    public void valuesAreIdentifierSafeNeverJsonEscaped() {
        // Only identifier/locale characters survive — a quote can never break out of the snapshot JSON.
        SearchStrategySelection selection = new SearchStrategySelection(
                SearchStrategySelection.STRATEGY_API_PROVIDER, "DATA\"_FOR_SEO", "GO{OGLE", "d\\e", "d e");
        assertEquals("DATA_FOR_SEO", selection.getProvider());
        assertEquals("GOOGLE", selection.getEngine());
        assertEquals("de", selection.getLanguage());
        assertEquals("de", selection.getCountry());
    }

    @Test
    public void theSelectionRoundTripsThroughTheStore() {
        MemoryStore store = new MemoryStore();
        ResearchRuntimeSettings.saveSearchStrategy(store, new SearchStrategySelection(
                SearchStrategySelection.STRATEGY_API_PROVIDER, "BRIGHT_DATA", "BING", "en", "us"));
        SearchStrategySelection loaded = ResearchRuntimeSettings.loadSearchStrategy(store);
        assertTrue(loaded.isApiProvider());
        assertEquals("BRIGHT_DATA", loaded.getProvider());
        assertEquals("BING", loaded.getEngine());
        assertEquals("en", loaded.getLanguage());
        assertEquals("us", loaded.getCountry());
    }

    @Test
    public void anEmptyStoreLoadsTheLegacyBrowserDefault() {
        assertFalse(ResearchRuntimeSettings.loadSearchStrategy(new MemoryStore()).isApiProvider());
        assertFalse(ResearchRuntimeSettings.loadSearchStrategy(null).isApiProvider());
    }

    @Test
    public void theLaunchEnvironmentCarriesTheSnapshotOnlyWhenOneWasPublished() {
        Map<String, String> withSnapshot = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "search.json", "reranker.json", "inference.json", "", "X:/session/search-strategy.json");
        assertEquals("X:/session/search-strategy.json",
                withSnapshot.get("ASKAI_SEARCH_STRATEGY_CONFIG"));

        Map<String, String> legacy = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "search.json", "reranker.json", "inference.json", "", "");
        assertFalse("legacy browser hands over NO strategy env (the agent's documented legacy path)",
                legacy.containsKey("ASKAI_SEARCH_STRATEGY_CONFIG"));
        // The pre-existing keys stay untouched.
        assertEquals("search.json", legacy.get("ASKAI_BROWSER_SEARCH_CONFIG"));
        assertEquals("reranker.json", legacy.get("ASKAI_RERANKER_CONFIG"));
    }
}
