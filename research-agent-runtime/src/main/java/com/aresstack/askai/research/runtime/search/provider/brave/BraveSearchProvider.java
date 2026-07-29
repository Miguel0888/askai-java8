package com.aresstack.askai.research.runtime.search.provider.brave;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfiguration;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRateLimitException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResponseException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderTemporaryException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderUnsupportedEngineException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;

/**
 * The productive Brave Search API adapter. It calls {@code GET <base>/res/v1/web/search} authenticated with
 * the {@code X-Subscription-Token} header and returns Brave's own web index. Brave is modelled as its own
 * engine: only {@link SearchEngine#BRAVE} (and {@link SearchEngine#PROVIDER_DEFAULT}) is supported — any
 * other engine fails with a typed unsupported-engine error, since this API does not proxy Google/Bing/etc.
 * {@link BraveResponseParser} keeps only the normal web results. The adapter owns endpoint, auth and error
 * mapping; it never opens target pages and never merges with other providers.
 */
public final class BraveSearchProvider implements SearchProvider {

    private static final SearchProviderId PROVIDER = SearchProviderId.BRAVE_SEARCH_API;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String DEFAULT_BASE_URL = "https://api.search.brave.com";
    private static final int MAX_COUNT = 20;

    /** The minimal HTTP seam so fixture/adapter tests run without a network. */
    public interface HttpTransport {
        HttpResponse get(String url, String subscriptionToken, int timeoutMillis) throws IOException;
    }

    /** A raw HTTP response (status + body) for the provider to map onto typed outcomes. */
    public static final class HttpResponse {
        public final int status;
        public final String body;

        public HttpResponse(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    private final SearchProviderConfiguration configuration;
    private final HttpTransport transport;

    public BraveSearchProvider(SearchProviderConfiguration configuration) {
        this(configuration, new UrlConnectionTransport());
    }

    public BraveSearchProvider(SearchProviderConfiguration configuration, HttpTransport transport) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.configuration = configuration;
        this.transport = transport;
    }

    @Override
    public SearchProviderId getProviderId() {
        return PROVIDER;
    }

    @Override
    public SearchProviderAvailability getAvailability() {
        return SearchProviderAvailability.AVAILABLE;
    }

    @Override
    public SearchProviderResult search(SearchProviderRequest request) {
        requireBraveEngine(request.getSearchEngine());
        String url = buildRequestUrl(configuration, request);
        String token = configuration.require("api_token");
        int timeout = configuration.getInt("timeout_millis", 30000);

        HttpResponse response;
        try {
            response = transport.get(url, token, timeout);
        } catch (IOException ex) {
            throw new SearchProviderTemporaryException(PROVIDER,
                    "Brave request failed: " + ex.getMessage(), ex);
        }
        mapHttpStatus(response);
        return BraveResponseParser.parse(request.getQuery(), response.body);
    }

    private static void mapHttpStatus(HttpResponse response) {
        int status = response.status;
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = "Brave returned HTTP " + status
                + (response.body.isEmpty() ? "" : ": " + truncate(response.body));
        if (status == 401 || status == 403) {
            throw new SearchProviderAuthenticationException(PROVIDER, detail);
        }
        if (status == 402) {
            throw new SearchProviderConfigurationException(PROVIDER, detail);
        }
        if (status == 429) {
            throw new SearchProviderRateLimitException(PROVIDER, detail);
        }
        if (status >= 500) {
            throw new SearchProviderTemporaryException(PROVIDER, detail);
        }
        throw new SearchProviderResponseException(PROVIDER, detail);
    }

    /** Build the web-search GET URL; count is clamped to Brave's maximum, locale applied when present. */
    static String buildRequestUrl(SearchProviderConfiguration config, SearchProviderRequest request) {
        String baseUrl = trimTrailingSlash(config.getOrDefault("base_url", DEFAULT_BASE_URL));
        int count = Math.max(1, Math.min(MAX_COUNT, request.getRequestedResultCount()));
        String language = request.getLanguage() != null
                ? request.getLanguage() : config.get("search_lang");
        String country = request.getCountry() != null
                ? request.getCountry() : config.get("country");
        StringBuilder sb = new StringBuilder(baseUrl).append("/res/v1/web/search?q=")
                .append(encode(request.getQuery()));
        sb.append("&count=").append(count);
        if (country != null) {
            sb.append("&country=").append(encode(country));
        }
        if (language != null) {
            sb.append("&search_lang=").append(encode(language));
        }
        return sb.toString();
    }

    private static void requireBraveEngine(SearchEngine engine) {
        if (engine != SearchEngine.BRAVE && engine != SearchEngine.PROVIDER_DEFAULT) {
            throw new SearchProviderUnsupportedEngineException(PROVIDER, engine);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 is always supported", ex);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String body) {
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }

    /** The productive transport: a plain {@link HttpURLConnection} GET with bounded timeouts. */
    private static final class UrlConnectionTransport implements HttpTransport {
        @Override
        public HttpResponse get(String url, String subscriptionToken, int timeoutMillis)
                throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setRequestProperty("X-Subscription-Token", subscriptionToken);
                connection.setRequestProperty("Accept", "application/json");
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                return new HttpResponse(status, readAll(stream));
            } finally {
                connection.disconnect();
            }
        }

        private static String readAll(InputStream stream) throws IOException {
            if (stream == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            try {
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } finally {
                stream.close();
            }
            return new String(buffer.toByteArray(), UTF_8);
        }
    }
}
