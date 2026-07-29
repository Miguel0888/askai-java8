package com.aresstack.askai.research.runtime.search.provider.dataforseo;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
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
 * Parses a stored DataForSEO envelope (no network): the productive response object with a {@code tasks}
 * array, each with a {@code result[].items[]} list. Only organic items with a URL + title and {@code
 * is_malicious != true} become hits; products, PAA and empty items are dropped; rank_group/rank_absolute
 * and optional fields map correctly; error status codes surface as typed exceptions.
 */
public class DataForSeoResponseParserTest {

    /** A realistic mixed SERP: two valid organic hits among malicious/non-organic/empty noise. */
    private static final String FIXTURE = "{"
            + "\"status_code\":20000,\"status_message\":\"Ok.\","
            + "\"tasks\":[{"
            + "  \"id\":\"task-1\",\"status_code\":20000,\"status_message\":\"Ok.\","
            + "  \"result\":[{"
            + "    \"keyword\":\"wearables\",\"type\":\"organic\",\"se_domain\":\"google.de\","
            + "    \"items\":["
            + "      {\"type\":\"organic\",\"rank_group\":1,\"rank_absolute\":2,\"domain\":\"mediamarkt.de\","
            + "       \"title\":\"Wearables kaufen\",\"description\":\"Smartwatches und Fitness-Tracker\","
            + "       \"url\":\"https://www.mediamarkt.de/de/category/wearables.html?srsltid=Afm&page=1\","
            + "       \"is_malicious\":false,\"timestamp\":\"2026-07-01 10:00:00 +00:00\","
            + "       \"check_url\":\"https://www.google.de/search?q=wearables\"},"
            + "      {\"type\":\"popular_products\",\"rank_group\":1,\"rank_absolute\":1},"
            + "      {\"type\":\"organic\",\"rank_group\":2,\"rank_absolute\":4,\"domain\":\"otto.de\","
            + "       \"title\":\"Wearables\",\"description\":\"bei OTTO\","
            + "       \"url\":\"https://www.otto.de/wearables\",\"is_malicious\":true},"
            + "      {\"type\":\"organic\",\"rank_group\":3,\"rank_absolute\":5,\"domain\":\"amazon.de\","
            + "       \"title\":\"Wearables Amazon\",\"url\":\"https://www.amazon.de/wearables\"},"
            + "      {\"type\":\"people_also_ask\",\"rank_group\":1,\"rank_absolute\":6},"
            + "      {\"type\":\"organic\",\"rank_group\":4,\"rank_absolute\":7,\"domain\":\"\","
            + "       \"title\":\"\",\"url\":\"https://empty-title.example/\"}"
            + "    ]"
            + "  }]"
            + "}]}";

    @Test
    public void extractsOnlyValidOrganicItems() {
        SearchProviderResult result = DataForSeoResponseParser.parse(
                SearchEngine.GOOGLE, "wearables", FIXTURE);
        assertEquals(SearchProviderId.DATA_FOR_SEO, result.getProviderId());
        assertEquals(SearchEngine.GOOGLE, result.getSearchEngine());

        List<SearchHit> hits = result.getHits();
        assertEquals("malicious, non-organic and empty items are dropped", 2, hits.size());

        SearchHit first = hits.get(0);
        assertEquals(SearchProviderId.DATA_FOR_SEO, first.getProviderId());
        assertEquals(SearchEngine.GOOGLE, first.getSearchEngine());
        assertEquals("rank_group is the organic rank", 1, first.getRank());
        assertEquals("rank_absolute is kept as diagnostics", 2, first.getAbsoluteRank());
        assertEquals("mediamarkt.de", first.getDomain());
        assertEquals("Wearables kaufen", first.getTitle());
        assertEquals("Smartwatches und Fitness-Tracker", first.getSnippet());
        assertEquals("the raw provider url is preserved (normalization happens in the strategy)",
                "https://www.mediamarkt.de/de/category/wearables.html?srsltid=Afm&page=1", first.getUrl());
        assertEquals("2026-07-01 10:00:00 +00:00", first.getPublishedAt());

        SearchHit second = hits.get(1);
        assertEquals(3, second.getRank());
        assertEquals("a missing description is tolerated as an empty snippet", "", second.getSnippet());
        assertNull("a missing timestamp is tolerated as null", second.getPublishedAt());
        assertEquals("https://www.amazon.de/wearables", second.getUrl());
    }

    @Test
    public void emptyResultIsSuccessNotFailure() {
        String body = "{\"status_code\":20000,\"tasks\":[{\"status_code\":20000,\"result\":null}]}";
        SearchProviderResult result = DataForSeoResponseParser.parse(SearchEngine.GOOGLE, "q", body);
        assertTrue(result.getHits().isEmpty());
    }

    @Test
    public void taskAuthErrorMapsToAuthenticationException() {
        String body = "{\"status_code\":20000,\"tasks\":[{\"status_code\":40101,"
                + "\"status_message\":\"Auth error.\"}]}";
        try {
            DataForSeoResponseParser.parse(SearchEngine.GOOGLE, "q", body);
            fail("expected authentication exception");
        } catch (SearchProviderAuthenticationException ex) {
            assertTrue(ex.getMessage().contains("40101"));
        }
    }

    @Test
    public void paymentStatusMapsToConfigurationException() {
        String body = "{\"status_code\":40200,\"status_message\":\"Payment required.\",\"tasks\":[]}";
        try {
            DataForSeoResponseParser.parse(SearchEngine.GOOGLE, "q", body);
            fail("expected configuration exception");
        } catch (SearchProviderConfigurationException ex) {
            assertTrue(ex.getMessage().contains("40200"));
        }
    }

    @Test
    public void nonJsonBodyIsRejectedTyped() {
        try {
            DataForSeoResponseParser.parse(SearchEngine.GOOGLE, "q", "<html>gateway timeout</html>");
            fail("expected response exception");
        } catch (SearchProviderResponseException ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("json"));
        }
    }
}
