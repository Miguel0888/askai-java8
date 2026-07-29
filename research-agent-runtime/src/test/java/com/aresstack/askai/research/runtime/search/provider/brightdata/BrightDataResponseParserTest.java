package com.aresstack.askai.research.runtime.search.provider.brightdata;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResponseException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Parses a stored Bright Data brd_json payload (no network): only the {@code organic} array is read, direct
 * target URLs are kept, and engine/proxy hosts and empty-title entries are dropped.
 */
public class BrightDataResponseParserTest {

    private static final String FIXTURE = "{"
            + "\"general\":{\"query\":\"wearables\"},"
            + "\"organic\":["
            + "  {\"link\":\"https://www.mediamarkt.de/de/category/wearables\",\"title\":\"Wearables\","
            + "   \"description\":\"Smartwatches\",\"rank\":1,\"global_rank\":2,"
            + "   \"display_link\":\"mediamarkt.de\"},"
            + "  {\"link\":\"https://www.google.com/search?q=more\",\"title\":\"More on Google\","
            + "   \"description\":\"transit\",\"rank\":2,\"global_rank\":3},"
            + "  {\"link\":\"https://www.amazon.de/wearables\",\"title\":\"Amazon Wearables\","
            + "   \"rank\":3,\"global_rank\":5},"
            + "  {\"link\":\"https://example.org/x\",\"title\":\"\",\"rank\":4,\"global_rank\":6}"
            + "]}";

    @Test
    public void keepsOnlyOrganicDirectTargetUrls() {
        SearchProviderResult result = BrightDataResponseParser.parse(
                SearchEngine.GOOGLE, "wearables", FIXTURE);
        assertEquals(SearchProviderId.BRIGHT_DATA, result.getProviderId());
        assertEquals(SearchEngine.GOOGLE, result.getSearchEngine());

        List<SearchHit> hits = result.getHits();
        assertEquals("engine-host and empty-title entries are dropped", 2, hits.size());

        SearchHit first = hits.get(0);
        assertEquals("https://www.mediamarkt.de/de/category/wearables", first.getUrl());
        assertEquals("rank is the organic rank", 1, first.getRank());
        assertEquals("global_rank is kept as diagnostics", 2, first.getAbsoluteRank());
        assertEquals("Smartwatches", first.getSnippet());
        assertEquals("www.mediamarkt.de", first.getDomain());
        assertNull("Bright Data organic items carry no timestamp", first.getPublishedAt());

        SearchHit second = hits.get(1);
        assertEquals("https://www.amazon.de/wearables", second.getUrl());
        assertEquals("a missing description is tolerated as an empty snippet", "", second.getSnippet());
    }

    @Test
    public void emptyOrganicIsSuccessNotFailure() {
        SearchProviderResult result = BrightDataResponseParser.parse(
                SearchEngine.GOOGLE, "q", "{\"organic\":[]}");
        assertTrue(result.getHits().isEmpty());
    }

    @Test
    public void missingOrganicKeyIsRejectedTyped() {
        try {
            BrightDataResponseParser.parse(SearchEngine.GOOGLE, "q", "{\"general\":{}}");
            fail("expected response exception");
        } catch (SearchProviderResponseException ex) {
            assertTrue(ex.getMessage().contains("organic"));
        }
    }

    @Test
    public void brightDataErrorFieldIsRejectedTyped() {
        try {
            BrightDataResponseParser.parse(SearchEngine.GOOGLE, "q", "{\"error\":\"zone blocked\"}");
            fail("expected response exception");
        } catch (SearchProviderResponseException ex) {
            assertTrue(ex.getMessage().contains("zone blocked"));
        }
    }

    @Test
    public void nonJsonBodyIsRejectedTyped() {
        try {
            BrightDataResponseParser.parse(SearchEngine.GOOGLE, "q", "<html>captcha</html>");
            fail("expected response exception");
        } catch (SearchProviderResponseException ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("json"));
        }
    }
}
