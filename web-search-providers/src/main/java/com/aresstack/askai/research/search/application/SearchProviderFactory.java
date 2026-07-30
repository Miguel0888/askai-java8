package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brave.BraveSearchConfigurationValidator;
import com.aresstack.askai.research.search.brave.BraveSearchProvider;
import com.aresstack.askai.research.search.brave.BraveSearchRequestMapper;
import com.aresstack.askai.research.search.brave.BraveSearchResponseMapper;
import com.aresstack.askai.research.search.brightdata.BrightDataRequestMapper;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfigurationValidator;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchProvider;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchResponseMapper;
import com.aresstack.askai.research.search.brightdata.BrightDataTargetUrlFactory;
import com.aresstack.askai.research.search.dataforseo.DataForSeoRequestMapper;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfigurationValidator;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchProvider;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchResponseMapper;
import com.aresstack.askai.research.search.http.AsyncHttpClientFactory;
import com.aresstack.askai.research.search.http.AsyncJsonHttpClient;
import com.aresstack.askai.research.search.http.EncryptedBasicAuthentication;
import com.aresstack.askai.research.search.http.EncryptedBearerAuthentication;
import com.aresstack.askai.research.search.http.EncryptedHeaderAuthentication;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.google.gson.Gson;

public final class SearchProviderFactory {

    private final Gson gson;
    private final AsyncHttpClientFactory httpClientFactory;
    private final SecretValueService secretValueService;

    public SearchProviderFactory(
            Gson gson,
            AsyncHttpClientFactory httpClientFactory,
            SecretValueService secretValueService) {

        this.gson = requireNonNull(gson, "gson");
        this.httpClientFactory = requireNonNull(
                httpClientFactory,
                "httpClientFactory");
        this.secretValueService = requireNonNull(
                secretValueService,
                "secretValueService");
    }

    public WebSearchProvider createBrave(
            BraveSearchConfiguration configuration) {

        new BraveSearchConfigurationValidator()
                .validate(configuration);
        AsyncJsonHttpClient client = new AsyncJsonHttpClient(
                httpClientFactory.create(
                        configuration.getTransport()),
                new EncryptedHeaderAuthentication(
                        "X-Subscription-Token",
                        "",
                        configuration.getApiKey(),
                        secretValueService),
                configuration.getTransport()
                        .getRequestTimeoutMillis());
        return new BraveSearchProvider(
                configuration,
                client,
                new BraveSearchRequestMapper(),
                new BraveSearchResponseMapper());
    }

    public WebSearchProvider createBrightData(
            BrightDataSearchConfiguration configuration) {

        new BrightDataSearchConfigurationValidator()
                .validate(configuration);
        AsyncJsonHttpClient client = new AsyncJsonHttpClient(
                httpClientFactory.create(
                        configuration.getTransport()),
                new EncryptedBearerAuthentication(
                        configuration.getApiKey(),
                        secretValueService),
                configuration.getTransport()
                        .getRequestTimeoutMillis());
        return new BrightDataSearchProvider(
                configuration,
                client,
                new BrightDataRequestMapper(
                        gson,
                        new BrightDataTargetUrlFactory()),
                new BrightDataSearchResponseMapper());
    }

    public WebSearchProvider createDataForSeo(
            DataForSeoSearchConfiguration configuration) {

        new DataForSeoSearchConfigurationValidator()
                .validate(configuration);
        AsyncJsonHttpClient client = new AsyncJsonHttpClient(
                httpClientFactory.create(
                        configuration.getTransport()),
                new EncryptedBasicAuthentication(
                        configuration.getUsername(),
                        configuration.getPassword(),
                        secretValueService),
                configuration.getTransport()
                        .getRequestTimeoutMillis());
        return new DataForSeoSearchProvider(
                configuration,
                client,
                new DataForSeoRequestMapper(gson),
                new DataForSeoSearchResponseMapper());
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}
