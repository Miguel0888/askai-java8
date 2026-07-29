package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session-start snapshot of the initial-search selection: which {@link StrategySelection} is active and,
 * for {@link StrategySelection#API_PROVIDER}, which {@link SearchProviderId}/{@link SearchEngine} and the
 * provider's own settings bag (credentials + locale + depth, resolved from the host's global secrets). A
 * running research session keeps this snapshot for its whole lifetime — the strategy is fixed once at start.
 */
public final class SearchStrategyConfiguration {

    private final StrategySelection strategy;
    private final SearchProviderId providerId;
    private final SearchEngine engine;
    private final String language;
    private final String country;
    private final Map<String, String> providerSettings;

    public SearchStrategyConfiguration(StrategySelection strategy, SearchProviderId providerId,
                                       SearchEngine engine, String language, String country,
                                       Map<String, String> providerSettings) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        if (strategy == StrategySelection.API_PROVIDER && (providerId == null || engine == null)) {
            throw new IllegalArgumentException(
                    "API_PROVIDER strategy requires a provider id and a search engine");
        }
        this.strategy = strategy;
        this.providerId = providerId;
        this.engine = engine;
        this.language = language;
        this.country = country;
        this.providerSettings = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(providerSettings == null
                        ? Collections.<String, String>emptyMap() : providerSettings));
    }

    /** The legacy-browser selection with no provider — the loop keeps its unchanged default strategy. */
    public static SearchStrategyConfiguration legacyBrowser() {
        return new SearchStrategyConfiguration(StrategySelection.LEGACY_BROWSER, null, null, null, null,
                Collections.<String, String>emptyMap());
    }

    public StrategySelection getStrategy() {
        return strategy;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getEngine() {
        return engine;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }

    public Map<String, String> getProviderSettings() {
        return providerSettings;
    }
}
