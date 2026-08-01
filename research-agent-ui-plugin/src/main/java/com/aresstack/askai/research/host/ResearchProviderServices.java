package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.application.SearchProviderFactory;
import com.aresstack.askai.research.search.config.GsonProviderConfigurationStore;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.dataforseo.DataForSeoPlayground;
import com.aresstack.askai.research.search.http.AsyncHttpClientFactory;
import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.google.gson.Gson;

/**
 * Host-side wiring of the provider configuration + secret services around the SAME files the research agent
 * reads ({@code ~/agents/research/providers/}). One instance per open settings dialog so a DataForSEO
 * draft, its save and the playground all share exactly one service, key store and Gson.
 */
public final class ResearchProviderServices {

    private final ProviderConfigurationPaths paths;
    private final SecretValueService secrets;
    private final ProviderConfigurationService configurationService;
    private final SearchProviderFactory providerFactory;
    private final Gson gson;

    public ResearchProviderServices() {
        this(ProviderConfigurationPaths.userHome());
    }

    public ResearchProviderServices(ProviderConfigurationPaths paths) {
        this.paths = paths;
        this.gson = new Gson();
        this.secrets = new SecretValueService(
                new AesGcmSecretCipher(new FileSecretKeyProvider(paths.getSecretKeyFile())));
        this.configurationService = new ProviderConfigurationService(
                paths, new GsonProviderConfigurationStore(), secrets);
        this.providerFactory = new SearchProviderFactory(gson, new AsyncHttpClientFactory(), secrets);
    }

    public ProviderConfigurationService configurationService() {
        return configurationService;
    }

    public SecretValueService secrets() {
        return secrets;
    }

    public DataForSeoPlayground dataForSeoPlayground() {
        return new DataForSeoPlayground(providerFactory, gson);
    }
}
