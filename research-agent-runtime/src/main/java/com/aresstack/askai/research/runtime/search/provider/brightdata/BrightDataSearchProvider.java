package com.aresstack.askai.research.runtime.search.provider.brightdata;

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
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;

/**
 * The productive Bright Data adapter. It queries the Bright Data Direct API
 * ({@code POST <base>/request}) with a Bearer token and a SERP zone, asking Bright Data to fetch the
 * engine's own result page with {@code brd_json=1} so the response body is parsed SERP JSON rather than raw
 * HTML. Supported engines (Google, Bing, Yandex, Baidu) are mapped explicitly to their query URLs; any other
 * engine fails with a typed unsupported-engine error. {@link BrightDataResponseParser} keeps only organic
 * direct target URLs. The adapter owns endpoints, auth and error mapping; it never opens target pages and
 * never merges with other providers. Credentials come only from the injected configuration.
 */
public final class BrightDataSearchProvider implements SearchProvider {

    private static final SearchProviderId PROVIDER = SearchProviderId.BRIGHT_DATA;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String DEFAULT_BASE_URL = "https://api.brightdata.com";

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

    public BrightDataSearchProvider(SearchProviderConfiguration configuration) {
        this(configuration, new UrlConnectionTransport());
    }

    public BrightDataSearchProvider(SearchProviderConfiguration configuration, HttpTransport transport) {
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
        String targetUrl = buildTargetUrl(configuration, request, actualEngine);

        String baseUrl = trimTrailingSlash(configuration.getOrDefault("base_url", DEFAULT_BASE_URL));
        String endpoint = baseUrl + "/request";
        String token = configuration.require("api_token");
        String zone = configuration.require("zone");
        String authorization = "Bearer " + token;
        int timeout = configuration.getInt("timeout_millis", 30000);

        String body = buildRequestBody(zone, targetUrl);

        HttpResponse response;
        try {
            response = transport.post(endpoint, authorization, body, timeout);
        } catch (IOException ex) {
            throw new SearchProviderTemporaryException(PROVIDER,
                    "Bright Data request failed: " + ex.getMessage(), ex);
        }
        mapHttpStatus(response);
        return BrightDataResponseParser.parse(actualEngine, request.getQuery(), response.body);
    }

    private static void mapHttpStatus(HttpResponse response) {
        int status = response.status;
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = "Bright Data returned HTTP " + status
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

    /** The Direct API body: fetch {@code url} through {@code zone}, returning the raw (brd_json) payload. */
    static String buildRequestBody(String zone, String targetUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"zone\":");
        SearchJson.appendString(sb, zone);
        sb.append(",\"url\":");
        SearchJson.appendString(sb, targetUrl);
        sb.append(",\"format\":\"raw\"");
        sb.append('}');
        return sb.toString();
    }

    /** Map the engine to its own SERP URL with {@code brd_json=1}; locale is applied per engine. */
    static String buildTargetUrl(SearchProviderConfiguration config, SearchProviderRequest request,
                                 SearchEngine actualEngine) {
        String query = encode(request.getQuery());
        int count = request.getRequestedResultCount();
        String language = request.getLanguage() != null
                ? request.getLanguage() : config.get("language");
        String country = request.getCountry() != null
                ? request.getCountry() : config.get("country");
        switch (actualEngine) {
            case GOOGLE: {
                StringBuilder sb = new StringBuilder("https://www.google.com/search?q=").append(query);
                sb.append("&brd_json=1&num=").append(count);
                if (country != null) {
                    sb.append("&gl=").append(encode(country));
                }
                if (language != null) {
                    sb.append("&hl=").append(encode(language));
                }
                return sb.toString();
            }
            case BING: {
                StringBuilder sb = new StringBuilder("https://www.bing.com/search?q=").append(query);
                sb.append("&brd_json=1&count=").append(count);
                if (language != null) {
                    sb.append("&setlang=").append(encode(language));
                }
                if (country != null) {
                    sb.append("&cc=").append(encode(country));
                }
                return sb.toString();
            }
            case YANDEX:
                return "https://yandex.com/search/?text=" + query + "&brd_json=1";
            case BAIDU:
                return "https://www.baidu.com/s?wd=" + query + "&brd_json=1&rn=" + count;
            default:
                throw new SearchProviderUnsupportedEngineException(PROVIDER, actualEngine);
        }
    }

    private static SearchEngine resolveEngine(SearchEngine requested) {
        return requested == SearchEngine.PROVIDER_DEFAULT ? SearchEngine.GOOGLE : requested;
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
