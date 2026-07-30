package com.aresstack.askai.research.runtime.search.provider.brave;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfiguration;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRateLimitException;
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

/** The Brave adapter over a fake transport: query URL, subscription-token auth, engine guard, status map. */
public class BraveSearchProviderTest {

    private static final String WEB_BODY = "{\"web\":{\"results\":[{\"type\":\"search_result\","
            + "\"title\":\"T\",\"url\":\"https://example.de/a\",\"description\":\"D\"}]}}";

    private static final class CapturingTransport implements BraveSearchProvider.HttpTransport {
        String url;
        String token;
        int status = 200;
        String responseBody = WEB_BODY;
        IOException failWith;

        public BraveSearchProvider.HttpResponse get(String url, String subscriptionToken, int timeoutMillis)
                throws IOException {
            this.url = url;
            this.token = subscriptionToken;
            if (failWith != null) {
                throw failWith;
            }
            return new BraveSearchProvider.HttpResponse(status, responseBody);
        }
    }

    private static SearchProviderConfiguration config() {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("api_token", "sub-token");
        return new SearchProviderConfiguration(SearchProviderId.BRAVE_SEARCH_API, settings);
    }

    @Test
    public void callsWebSearchWithSubscriptionTokenAndLocale() {
        CapturingTransport transport = new CapturingTransport();
        BraveSearchProvider provider = new BraveSearchProvider(config(), transport);

        SearchProviderResult result = provider.search(
                new SearchProviderRequest("wearables", SearchEngine.BRAVE, 5, "de", "DE"));

        assertTrue(transport.url.contains("/res/v1/web/search?q=wearables"));
        assertTrue(transport.url.contains("&count=5"));
        assertTrue(transport.url.contains("&country=DE"));
        assertTrue(transport.url.contains("&search_lang=de"));
        assertEquals("sub-token", transport.token);

        assertEquals(1, result.getHits().size());
        assertEquals("https://example.de/a", result.getHits().get(0).getUrl());
        assertEquals(SearchEngine.BRAVE, result.getHits().get(0).getSearchEngine());
    }

    @Test
    public void countIsClampedToBraveMaximum() {
        CapturingTransport transport = new CapturingTransport();
        new BraveSearchProvider(config(), transport)
                .search(new SearchProviderRequest("q", SearchEngine.BRAVE, 50, null, null));
        assertTrue("count clamped to 20", transport.url.contains("&count=20"));
    }

    @Test
    public void providerDefaultEngineIsTreatedAsBrave() {
        CapturingTransport transport = new CapturingTransport();
        new BraveSearchProvider(config(), transport)
                .search(new SearchProviderRequest("q", SearchEngine.PROVIDER_DEFAULT, 10, null, null));
        assertTrue(transport.url.contains("/res/v1/web/search"));
    }

    @Test
    public void nonBraveEngineFailsBeforeAnyCall() {
        CapturingTransport transport = new CapturingTransport();
        try {
            new BraveSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.GOOGLE, 10, null, null));
            fail("expected unsupported engine exception");
        } catch (SearchProviderUnsupportedEngineException ex) {
            assertEquals(SearchProviderId.BRAVE_SEARCH_API, ex.getProviderId());
        }
        assertEquals("no request must be sent for an unsupported engine", null, transport.url);
    }

    @Test
    public void httpUnauthorizedMapsToAuthenticationException() {
        CapturingTransport transport = new CapturingTransport();
        transport.status = 401;
        transport.responseBody = "unauthorized";
        try {
            new BraveSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.BRAVE, 10, null, null));
            fail("expected authentication exception");
        } catch (SearchProviderAuthenticationException ex) {
            assertTrue(ex.getMessage().contains("401"));
        }
    }

    @Test
    public void httpTooManyRequestsMapsToRateLimitException() {
        CapturingTransport transport = new CapturingTransport();
        transport.status = 429;
        transport.responseBody = "slow down";
        try {
            new BraveSearchProvider(config(), transport)
                    .search(new SearchProviderRequest("q", SearchEngine.BRAVE, 10, null, null));
            fail("expected rate limit exception");
        } catch (SearchProviderRateLimitException ex) {
            assertTrue(ex.isRetryable());
        }
    }
}
