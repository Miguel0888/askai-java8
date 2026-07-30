package com.aresstack.askai.research.runtime.search.provider.brave;

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
 * Parses a stored Brave web-search payload (no network): only web.results are read, rank comes from the
 * order, the domain comes from meta_url, and empty-title entries are dropped.
 */
public class BraveResponseParserTest {

    private static final String FIXTURE = "{"
            + "\"type\":\"search\",\"query\":{\"original\":\"wearables\"},"
            + "\"web\":{\"type\":\"search\",\"results\":["
            + "  {\"type\":\"search_result\",\"title\":\"Wearables\","
            + "   \"url\":\"https://www.mediamarkt.de/de/wearables\",\"description\":\"Smartwatches\","
            + "   \"meta_url\":{\"hostname\":\"www.mediamarkt.de\"},\"page_age\":\"2026-06-01T00:00:00\"},"
            + "  {\"type\":\"search_result\",\"title\":\"Amazon Wearables\","
            + "   \"url\":\"https://www.amazon.de/wearables\",\"description\":\"buy\"},"
            + "  {\"type\":\"search_result\",\"title\":\"\",\"url\":\"https://empty.example/\","
            + "   \"description\":\"x\"}"
            + "]}}";

    @Test
    public void extractsWebResultsWithOrderRankAndMetaDomain() {
        SearchProviderResult result = BraveResponseParser.parse("wearables", FIXTURE);
        assertEquals(SearchProviderId.BRAVE_SEARCH_API, result.getProviderId());
        assertEquals(SearchEngine.BRAVE, result.getSearchEngine());

        List<SearchHit> hits = result.getHits();
        assertEquals("the empty-title entry is dropped", 2, hits.size());

        SearchHit first = hits.get(0);
        assertEquals(SearchEngine.BRAVE, first.getSearchEngine());
        assertEquals("rank is the 1-based web position", 1, first.getRank());
        assertEquals("https://www.mediamarkt.de/de/wearables", first.getUrl());
        assertEquals("Smartwatches", first.getSnippet());
        assertEquals("domain comes from meta_url.hostname", "www.mediamarkt.de", first.getDomain());
        assertEquals("2026-06-01T00:00:00", first.getPublishedAt());

        SearchHit second = hits.get(1);
        assertEquals(2, second.getRank());
        assertEquals("domain falls back to the URL host", "www.amazon.de", second.getDomain());
        assertNull("a missing page_age is tolerated as null", second.getPublishedAt());
    }

    @Test
    public void missingWebBlockIsSuccessNotFailure() {
        SearchProviderResult result = BraveResponseParser.parse("q", "{\"type\":\"search\"}");
        assertTrue(result.getHits().isEmpty());
    }

    @Test
    public void malformedWebResultsIsRejectedTyped() {
        try {
            BraveResponseParser.parse("q", "{\"web\":{\"results\":\"nope\"}}");
            fail("expected response exception");
        } catch (SearchProviderResponseException ex) {
            assertTrue(ex.getMessage().contains("web.results"));
        }
    }

    @Test
    public void nonJsonBodyIsRejectedTyped() {
        try {
            BraveResponseParser.parse("q", "<html>error</html>");
            fail("expected response exception");
        } catch (SearchProviderResponseException ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("json"));
        }
    }
}
