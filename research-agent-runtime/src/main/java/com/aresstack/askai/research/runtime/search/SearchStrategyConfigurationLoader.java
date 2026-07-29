package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchJson;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes the initial-search strategy snapshot (JSON) the host publishes at session start. There is no live
 * reconfiguration and NO silent fallback: a present-but-invalid snapshot is a hard error, never a quiet
 * switch back to the legacy browser search. Absent snapshot text is treated as {@link
 * SearchStrategyConfiguration#legacyBrowser()} so nothing changes for existing sessions.
 *
 * <p>Shape:</p>
 * <pre>
 * { "strategy": "API_PROVIDER", "provider": "DATA_FOR_SEO", "engine": "GOOGLE",
 *   "language": "de", "country": "de",
 *   "provider_settings": { "login": "…", "password": "…", "location_name": "Germany",
 *                          "language_code": "de", "device": "desktop", "os": "windows", "depth": "10" } }
 * </pre>
 */
public final class SearchStrategyConfigurationLoader {

    /** A contract-invalid snapshot; the caller fails the session start visibly. */
    public static final class InvalidConfigurationException extends RuntimeException {
        public InvalidConfigurationException(String message) {
            super(message);
        }
    }

    private SearchStrategyConfigurationLoader() {
    }

    public static SearchStrategyConfiguration parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return SearchStrategyConfiguration.legacyBrowser();
        }
        Object root;
        try {
            root = SearchJson.parse(json);
        } catch (SearchJson.JsonParseException ex) {
            throw new InvalidConfigurationException(
                    "search strategy snapshot is not valid JSON: " + ex.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new InvalidConfigurationException("search strategy snapshot root is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) root;

        StrategySelection strategy = parseStrategy(string(object.get("strategy")));
        if (strategy == StrategySelection.LEGACY_BROWSER) {
            return SearchStrategyConfiguration.legacyBrowser();
        }

        SearchProviderId providerId = parseProvider(string(object.get("provider")));
        SearchEngine engine = parseEngine(string(object.get("engine")));
        String language = string(object.get("language"));
        String country = string(object.get("country"));
        Map<String, String> providerSettings = stringMap(object.get("provider_settings"));
        return new SearchStrategyConfiguration(strategy, providerId, engine, language, country,
                providerSettings);
    }

    private static StrategySelection parseStrategy(String value) {
        if (value == null) {
            throw new InvalidConfigurationException("search strategy snapshot has no 'strategy'");
        }
        try {
            return StrategySelection.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationException("unknown strategy '" + value + "'");
        }
    }

    private static SearchProviderId parseProvider(String value) {
        if (value == null) {
            throw new InvalidConfigurationException("API_PROVIDER strategy has no 'provider'");
        }
        try {
            return SearchProviderId.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationException("unknown provider '" + value + "'");
        }
    }

    private static SearchEngine parseEngine(String value) {
        if (value == null) {
            throw new InvalidConfigurationException("API_PROVIDER strategy has no 'engine'");
        }
        try {
            return SearchEngine.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationException("unknown engine '" + value + "'");
        }
    }

    private static String string(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                    result.put((String) entry.getKey(), (String) entry.getValue());
                }
            }
        }
        return result;
    }
}
