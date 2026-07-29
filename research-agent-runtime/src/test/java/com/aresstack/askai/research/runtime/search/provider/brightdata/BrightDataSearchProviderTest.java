package com.aresstack.askai.research.runtime.search.provider.brightdata;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfiguration;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderUnsupportedEngineException;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The Bright Data adapter over a fake transport: engine->URL, Bearer auth, /request body, status mapping. */
public class BrightDataSearchProviderTest {

    private static final String ORGANIC_BODY = "{\"organic\":[{\"link\":\"https://example.de/a\","
            + "\"title\":\"T\",\"description\":\"D\",\"rank\":1,\"global_rank\":1}]}";

    private static final class CapturingTransport implements BrightDataSearchProvider.HttpTransport {
        String url;
        String authorization;
        String body;
        int status = 200;
        String responseBody = ORGANIC_BODY;
        IOException failWith;

        public BrightDataSearchProvider.HttpResponse post(String url, String authorization, String jsonBody,
                                                          int timeoutMillis) throws IOException {
            this.url = url;
            this.authorization = authorization;
            this.body = jsonBody;
            if (failWith != null) {
                throw failWith;
            }
            return new BrightDataSearchProvider.HttpResponse(status, responseBody);
        }
    }

    private static SearchProviderConfiguration config() {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("api_token", "token");
        settings.put("zone", "serp");
        return new SearchProviderConfiguration(SearchProviderId.BRIGHT_DATA, settings);
    }

    @Test
    public void callsDirectApiWithBearerAuthAndBrdJsonGoogleUrl() {
        CapturingTransport transport = new CapturingTransport();
        BrightDataSearchProvider provider = new BrightDataSearchProvider(config(), transport);

        SearchProviderResult result = provider.search(
                new SearchProviderRequest("wearables", SearchEngine.GOOGLE, 5, "de", "de"));

        assertTrue(transport.url.endsWith("/request"));
        assertEquals("Bearer token", transport.authorization);
        assertTrue(transport.body.contains("\"zone\":\"serp\""));
        assertTrue(transport.body.contains("\"format\":\"raw\""));
        assertTrue(transport.body.contains(
                "q=wearables&brd_json=1&num=5&gl=de&hl=de"));

        assertEquals(1, result.getHits().size());
        assertEquals("https://example.de/a", result.getHits().get(0).getUrl());
    }

    @Test
    public void mapsBingEngineToItsOwnUrl() {
        CapturingTransport transport = new CapturingTransport();
        new BrightDataSearchProvider(config(), transport)
                .search(new SearchProviderRequest("q", SearchEngine.BING, 10, null, null));
        assertTrue(transport.body.contains("https://www.bing.com/search?q=q&brd_json=1&count=10"));
    }

    @Test
    public void unsupportedEngineFailsBeforeAnyCall() {
        CapturingTransport transport = new CapturingTransport();
        try {
            new BrightDataSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.DUCK_DUCK_GO, 10, null, null));
            fail("expected unsupported engine exception");
        } catch (SearchProviderUnsupportedEngineException ex) {
            assertEquals(SearchProviderId.BRIGHT_DATA, ex.getProviderId());
        }
        assertEquals("no request must be sent for an unsupported engine", null, transport.url);
    }

    @Test
    public void httpUnauthorizedMapsToAuthenticationException() {
        CapturingTransport transport = new CapturingTransport();
        transport.status = 401;
        transport.responseBody = "unauthorized";
        try {
            new BrightDataSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.GOOGLE, 10, null, null));
            fail("expected authentication exception");
        } catch (SearchProviderAuthenticationException ex) {
            assertTrue(ex.getMessage().contains("401"));
        }
    }
}
