package com.aresstack.askai.research.runtime.search.provider;

import com.aresstack.askai.research.runtime.search.provider.brightdata.BrightDataSearchProvider;
import com.aresstack.askai.research.runtime.search.provider.dataforseo.DataForSeoSearchProvider;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The productive registry. It binds ONLY the implemented providers; today that is DATA_FOR_SEO
 * ({@link DataForSeoSearchProvider}) and BRIGHT_DATA ({@link BrightDataSearchProvider}), each built lazily
 * from the {@link SearchProviderConfigurationSource}. Every other catalogued id — including BRAVE_SEARCH_API,
 * which still has its own provider-specific interface and becomes productive in a follow-up slice — is NOT
 * bound: {@link #requireImplementedProvider(SearchProviderId)} throws for it and creates no object. The
 * catalogue still lists ALL ids so the settings UI can show the not-yet-implemented ones.
 */
public final class DefaultSearchProviderRegistry implements SearchProviderRegistry {

    /** The single source of truth for which ids are productively bound today. */
    private static final Set<SearchProviderId> IMPLEMENTED =
            EnumSet.of(SearchProviderId.DATA_FOR_SEO, SearchProviderId.BRIGHT_DATA);

    private final SearchProviderConfigurationSource configurationSource;

    public DefaultSearchProviderRegistry(SearchProviderConfigurationSource configurationSource) {
        if (configurationSource == null) {
            throw new IllegalArgumentException("configurationSource must not be null");
        }
        this.configurationSource = configurationSource;
    }

    @Override
    public SearchProvider requireImplementedProvider(SearchProviderId providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException("Provider id must not be null");
        }
        if (providerId == SearchProviderId.DATA_FOR_SEO) {
            return new DataForSeoSearchProvider(configurationSource.load(SearchProviderId.DATA_FOR_SEO));
        }
        if (providerId == SearchProviderId.BRIGHT_DATA) {
            return new BrightDataSearchProvider(configurationSource.load(SearchProviderId.BRIGHT_DATA));
        }
        // No stub, no object: an unimplemented id fails explicitly here.
        throw new SearchProviderNotImplementedException(providerId);
    }

    @Override
    public List<SearchProviderDescriptor> getDescriptors() {
        List<SearchProviderDescriptor> descriptors = new ArrayList<SearchProviderDescriptor>();
        for (SearchProviderId id : SearchProviderId.values()) {
            SearchProviderImplementationStatus status = IMPLEMENTED.contains(id)
                    ? SearchProviderImplementationStatus.IMPLEMENTED
                    : SearchProviderImplementationStatus.NOT_IMPLEMENTED;
            descriptors.add(new SearchProviderDescriptor(id, displayName(id), status));
        }
        return descriptors;
    }

    private static String displayName(SearchProviderId id) {
        switch (id) {
            case BRAVE_SEARCH_API: return "Brave Search API";
            case BRIGHT_DATA: return "Bright Data";
            case DATA_FOR_SEO: return "DataForSEO";
            case SERP_API: return "SerpApi";
            case AWS_AGENTCORE_WEB_SEARCH: return "AWS AgentCore Web Search";
            case AZURE_OPENAI_WEB_SEARCH: return "Azure OpenAI Web Search";
            case YANDEX_CLOUD_SEARCH_API: return "Yandex Cloud Search API";
            case BAIDU_QIANFAN_WEB_SEARCH: return "Baidu Qianfan Web Search";
            case GOOGLE_CUSTOM_SEARCH_JSON_API: return "Google Custom Search JSON API";
            case BING_WEB_SEARCH_API: return "Bing Web Search API";
            case SEARXNG: return "SearXNG";
            case TAVILY: return "Tavily";
            case EXA: return "Exa";
            default: return id.name();
        }
    }
}
