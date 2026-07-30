package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.DefaultSearchProviderRegistry;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfiguration;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationSource;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRegistry;

/**
 * The single place that turns a session {@link SearchStrategyConfiguration} into a {@link SearchStrategy}.
 * Provider construction goes through the {@link SearchProviderRegistry} here — no scattered {@code switch}
 * blocks in the agent or the loop. For {@link StrategySelection#LEGACY_BROWSER} it returns {@code null},
 * meaning "keep the loop's unchanged default browser strategy"; for {@link StrategySelection#API_PROVIDER}
 * it resolves the configured provider and wraps it in a {@link SingleProviderSearchStrategy}.
 */
public final class ResearchSearchStrategyFactory {

    private ResearchSearchStrategyFactory() {
    }

    /**
     * @return the API-provider strategy to inject, or {@code null} for the legacy browser selection (the
     *         loop then keeps its own default strategy — there is never a silent provider substitution).
     */
    public static SearchStrategy create(final SearchStrategyConfiguration config) {
        if (config.getStrategy() == StrategySelection.LEGACY_BROWSER) {
            return null;
        }
        SearchProviderConfigurationSource source = new SearchProviderConfigurationSource() {
            public SearchProviderConfiguration load(SearchProviderId providerId) {
                return new SearchProviderConfiguration(providerId, config.getProviderSettings());
            }
        };
        SearchProviderRegistry registry = new DefaultSearchProviderRegistry(source);
        SearchProvider provider = registry.requireImplementedProvider(config.getProviderId());
        return new SingleProviderSearchStrategy(provider, config.getEngine());
    }
}
