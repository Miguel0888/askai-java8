package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.config.GsonProviderConfigurationStore;
import com.aresstack.askai.research.search.config.ProviderConfigurationBundle;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.http.AsyncHttpClientFactory;
import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

public final class WebSearchProvidersModule
        implements AutoCloseable {

    private final ProviderConfigurationService configurationService;
    private final SearchProviderFactory providerFactory;
    private WebSearchProviderRegistry providerRegistry;

    private WebSearchProvidersModule(
            ProviderConfigurationService configurationService,
            SearchProviderFactory providerFactory) {

        this.configurationService = configurationService;
        this.providerFactory = providerFactory;
        this.providerRegistry = createRegistry(
                configurationService.loadOrCreateAll());
    }

    public static WebSearchProvidersModule openUserHome() {
        return open(ProviderConfigurationPaths.userHome());
    }

    public static WebSearchProvidersModule open(
            ProviderConfigurationPaths paths) {

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .disableHtmlEscaping()
                .create();

        SecretValueService secretValueService =
                new SecretValueService(
                        new AesGcmSecretCipher(
                                new FileSecretKeyProvider(
                                        paths.getSecretKeyFile())));

        ProviderConfigurationService configurationService =
                new ProviderConfigurationService(
                        paths,
                        new GsonProviderConfigurationStore(gson),
                        secretValueService);

        SearchProviderFactory factory =
                new SearchProviderFactory(
                        gson,
                        new AsyncHttpClientFactory(),
                        secretValueService);

        return new WebSearchProvidersModule(
                configurationService,
                factory);
    }

    public ProviderConfigurationService
            getConfigurationService() {

        return configurationService;
    }

    public synchronized WebSearchProviderRegistry
            getProviderRegistry() {

        return providerRegistry;
    }

    public synchronized void reloadProviders() {
        ProviderConfigurationBundle configurations =
                configurationService.loadOrCreateAll();
        WebSearchProviderRegistry replacement =
                createRegistry(configurations);
        WebSearchProviderRegistry previous = providerRegistry;
        providerRegistry = replacement;
        previous.close();
    }

    private WebSearchProviderRegistry createRegistry(
            ProviderConfigurationBundle configurations) {

        List<WebSearchProvider> providers =
                new ArrayList<WebSearchProvider>();

        if (configurations.getBrave().isEnabled()) {
            providers.add(providerFactory.createBrave(
                    configurations.getBrave()));
        }
        if (configurations.getBrightData().isEnabled()) {
            providers.add(providerFactory.createBrightData(
                    configurations.getBrightData()));
        }
        if (configurations.getDataForSeo().isEnabled()) {
            providers.add(providerFactory.createDataForSeo(
                    configurations.getDataForSeo()));
        }

        return new WebSearchProviderRegistry(providers);
    }

    @Override
    public synchronized void close() {
        providerRegistry.close();
    }
}
