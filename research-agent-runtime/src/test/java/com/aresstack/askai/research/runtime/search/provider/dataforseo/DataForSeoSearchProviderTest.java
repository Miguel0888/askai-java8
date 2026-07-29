package com.aresstack.askai.research.runtime.search.provider.dataforseo;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfiguration;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderUnsupportedEngineException;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The DataForSEO adapter over a fake transport: engine→path, Basic auth, request body and status mapping. */
public class DataForSeoSearchProviderTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String ORGANIC_BODY = "{\"status_code\":20000,\"tasks\":[{\"status_code\":20000,"
            + "\"result\":[{\"items\":[{\"type\":\"organic\",\"rank_group\":1,\"rank_absolute\":1,"
            + "\"domain\":\"example.de\",\"title\":\"T\",\"description\":\"D\","
            + "\"url\":\"https://example.de/a\"}]}]}]}";

    private static final class CapturingTransport implements DataForSeoSearchProvider.HttpTransport {
        String url;
        String authorization;
        String body;
        int status = 200;
        String responseBody = ORGANIC_BODY;
        IOException failWith;

        public DataForSeoSearchProvider.HttpResponse post(String url, String authorization, String jsonBody,
                                                          int timeoutMillis) throws IOException {
            this.url = url;
            this.authorization = authorization;
            this.body = jsonBody;
            if (failWith != null) {
                throw failWith;
            }
            return new DataForSeoSearchProvider.HttpResponse(status, responseBody);
        }
    }

    private static SearchProviderConfiguration config() {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("login", "user");
        settings.put("password", "secret");
        settings.put("location_name", "Germany");
        settings.put("language_code", "de");
        return new SearchProviderConfiguration(SearchProviderId.DATA_FOR_SEO, settings);
    }

    @Test
    public void callsGoogleOrganicLiveAdvancedWithBasicAuthAndTaskBody() {
        CapturingTransport transport = new CapturingTransport();
        DataForSeoSearchProvider provider = new DataForSeoSearchProvider(config(), transport);

        SearchProviderResult result = provider.search(
                new SearchProviderRequest("wearables", SearchEngine.GOOGLE, 5, null, null));

        assertTrue(transport.url.endsWith("/v3/serp/google/organic/live/advanced"));
        assertTrue(transport.authorization.startsWith("Basic "));
        String decoded = new String(Base64.getDecoder().decode(
                transport.authorization.substring("Basic ".length())), UTF_8);
        assertEquals("user:secret", decoded);
        assertTrue(transport.body.startsWith("[{"));
        assertTrue(transport.body.contains("\"keyword\":\"wearables\""));
        assertTrue(transport.body.contains("\"location_name\":\"Germany\""));
        assertTrue(transport.body.contains("\"language_code\":\"de\""));
        assertTrue(transport.body.contains("\"depth\":5"));

        assertEquals(1, result.getHits().size());
        assertEquals("https://example.de/a", result.getHits().get(0).getUrl());
    }

    @Test
    public void requestLanguageOverridesConfiguredLanguage() {
        CapturingTransport transport = new CapturingTransport();
        new DataForSeoSearchProvider(config(), transport)
                .search(new SearchProviderRequest("q", SearchEngine.GOOGLE, 10, "en", null));
        assertTrue(transport.body.contains("\"language_code\":\"en\""));
    }

    @Test
    public void unsupportedEngineFailsBeforeAnyCall() {
        CapturingTransport transport = new CapturingTransport();
        try {
            new DataForSeoSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.DUCK_DUCK_GO, 10, null, null));
            fail("expected unsupported engine exception");
        } catch (SearchProviderUnsupportedEngineException ex) {
            assertEquals(SearchProviderId.DATA_FOR_SEO, ex.getProviderId());
        }
        assertEquals("no request must be sent for an unsupported engine", null, transport.url);
    }

    @Test
    public void httpUnauthorizedMapsToAuthenticationException() {
        CapturingTransport transport = new CapturingTransport();
        transport.status = 401;
        transport.responseBody = "unauthorized";
        try {
            new DataForSeoSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.GOOGLE, 10, null, null));
            fail("expected authentication exception");
        } catch (SearchProviderAuthenticationException ex) {
            assertTrue(ex.getMessage().contains("401"));
        }
    }
}
