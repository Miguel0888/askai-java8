package com.aresstack.askai.research.runtime.search.provider.dataforseo;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchJson;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Base64;

/**
 * The productive DataForSEO adapter. It calls {@code POST /v3/serp/<engine>/organic/live/advanced} with
 * HTTP Basic auth (the separate API login/password), sends exactly one task in the JSON array, and delegates
 * the productive response envelope to {@link DataForSeoResponseParser}. It owns endpoints, auth, engine
 * validation and error mapping; it never opens target pages and never merges with other providers.
 * Credentials and parameters come only from the injected {@link SearchProviderConfiguration} (resolved from
 * the host's global secret mechanism), never from Swing or the project directory.
 */
public final class DataForSeoSearchProvider implements SearchProvider {

    private static final SearchProviderId PROVIDER = SearchProviderId.DATA_FOR_SEO;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String DEFAULT_BASE_URL = "https://api.dataforseo.com";

    /** The minimal HTTP seam so fixture/adapter tests run without a network. */
    public interface HttpTransport {
        HttpResponse post(String url, String authorization, String jsonBody, int timeoutMillis)
                throws IOException;
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

    public DataForSeoSearchProvider(SearchProviderConfiguration configuration) {
        this(configuration, new UrlConnectionTransport());
    }

    public DataForSeoSearchProvider(SearchProviderConfiguration configuration, HttpTransport transport) {
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
        SearchEngine actualEngine = resolveEngine(request.getSearchEngine());
        String enginePath = enginePath(actualEngine);

        String baseUrl = trimTrailingSlash(configuration.getOrDefault("base_url", DEFAULT_BASE_URL));
        String endpoint = baseUrl + "/v3/serp/" + enginePath + "/organic/live/advanced";
        String login = configuration.require("login");
        String password = configuration.require("password");
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (login + ":" + password).getBytes(UTF_8));
        int timeout = configuration.getInt("timeout_millis", 30000);

        String body = buildRequestBody(configuration, request, actualEngine);

        HttpResponse response;
        try {
            response = transport.post(endpoint, authorization, body, timeout);
        } catch (IOException ex) {
            throw new SearchProviderTemporaryException(PROVIDER,
                    "DataForSEO request failed: " + ex.getMessage(), ex);
        }
        mapHttpStatus(response);
        return DataForSeoResponseParser.parse(actualEngine, request.getQuery(), response.body);
    }

    private static void mapHttpStatus(HttpResponse response) {
        int status = response.status;
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = "DataForSEO returned HTTP " + status
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

    /** Build the single-task JSON array. Engine lives in the URL path; the task carries locale + depth. */
    static String buildRequestBody(SearchProviderConfiguration config, SearchProviderRequest request,
                                   SearchEngine actualEngine) {
        StringBuilder sb = new StringBuilder();
        sb.append("[{");
        sb.append("\"keyword\":");
        SearchJson.appendString(sb, request.getQuery());

        String locationCode = config.get("location_code");
        if (locationCode != null) {
            sb.append(",\"location_code\":").append(locationCodeOrThrow(config));
        } else {
            String locationName = config.get("location_name");
            if (locationName == null) {
                throw new SearchProviderConfigurationException(PROVIDER,
                        "DataForSEO requires 'location_code' or 'location_name'");
            }
            sb.append(",\"location_name\":");
            SearchJson.appendString(sb, locationName);
        }

        String language = request.getLanguage() != null
                ? request.getLanguage() : config.getOrDefault("language_code", "en");
        sb.append(",\"language_code\":");
        SearchJson.appendString(sb, language);

        sb.append(",\"device\":");
        SearchJson.appendString(sb, config.getOrDefault("device", "desktop"));
        sb.append(",\"os\":");
        SearchJson.appendString(sb, config.getOrDefault("os", "windows"));
        sb.append(",\"depth\":").append(request.getRequestedResultCount());
        sb.append("}]");
        return sb.toString();
    }

    private static String locationCodeOrThrow(SearchProviderConfiguration config) {
        String value = config.get("location_code");
        try {
            Integer.parseInt(value);
            return value;
        } catch (NumberFormatException ex) {
            throw new SearchProviderConfigurationException(PROVIDER,
                    "DataForSEO 'location_code' must be an integer (was '" + value + "')");
        }
    }

    private static SearchEngine resolveEngine(SearchEngine requested) {
        return requested == SearchEngine.PROVIDER_DEFAULT ? SearchEngine.GOOGLE : requested;
    }

    private static String enginePath(SearchEngine engine) {
        switch (engine) {
            case GOOGLE: return "google";
            case BING: return "bing";
            case YANDEX: return "yandex";
            case BAIDU: return "baidu";
            default:
                throw new SearchProviderUnsupportedEngineException(PROVIDER, engine);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String body) {
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }

    /** The productive transport: a plain {@link HttpURLConnection} POST with bounded timeouts. */
    private static final class UrlConnectionTransport implements HttpTransport {
        @Override
        public HttpResponse post(String url, String authorization, String jsonBody, int timeoutMillis)
                throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", authorization);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);

                byte[] payload = jsonBody.getBytes(UTF_8);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    out.close();
                }

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
