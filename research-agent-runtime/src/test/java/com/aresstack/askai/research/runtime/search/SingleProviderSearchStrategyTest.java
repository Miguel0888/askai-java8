package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The API strategy maps provider hits onto the neutral candidate the reranker consumes: the URL is
 * normalized for navigation while the raw provider URL is kept as provenance, and there are no transit
 * hosts/challenges. Budget and cancellation are honoured before the provider is ever called.
 */
public class SingleProviderSearchStrategyTest {

    private static final class FakeProvider implements SearchProvider {
        SearchProviderRequest received;
        final List<SearchHit> hits = new ArrayList<SearchHit>();

        public SearchProviderId getProviderId() {
            return SearchProviderId.DATA_FOR_SEO;
        }

        public SearchProviderResult search(SearchProviderRequest request) {
            this.received = request;
            return new SearchProviderResult(SearchProviderId.DATA_FOR_SEO, SearchEngine.GOOGLE, hits);
        }

        public SearchProviderAvailability getAvailability() {
            return SearchProviderAvailability.AVAILABLE;
        }
    }

    private static final SearchBudgetGate ALLOW = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return true;
        }
    };
    private static final SearchBudgetGate DENY = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return false;
        }
    };

    @Test
    public void mapsHitsToNormalizedCandidatesWithNoTransitOrChallenges() {
        FakeProvider provider = new FakeProvider();
        provider.hits.add(new SearchHit(SearchProviderId.DATA_FOR_SEO, SearchEngine.GOOGLE, "wearables", 1,
                "https://shop.example/de/p.html?srsltid=Afm&page=2", "Shop", "buy wearables",
                "shop.example", 3, "2026-07-01 00:00:00 +00:00"));

        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("wearables", 10, "de", "de"), CancellationSignal.NONE,
                        ALLOW);

        assertEquals("the neutral request reaches the provider with the configured engine",
                SearchEngine.GOOGLE, provider.received.getSearchEngine());
        assertEquals("wearables", provider.received.getQuery());
        assertTrue("API results carry no search-engine transit host", result.providerHosts.isEmpty());
        assertTrue("API results carry no challenges", result.challenges.isEmpty());

        assertEquals(1, result.candidates.size());
        SearchResultCandidate candidate = result.candidates.get(0);
        assertEquals("navigated URL is normalized (srsltid stripped, page kept)",
                "https://shop.example/de/p.html?page=2", candidate.resolvedTargetUrl);
        assertEquals("raw provider URL kept as provenance",
                "https://shop.example/de/p.html?srsltid=Afm&page=2", candidate.rawSearchHref);
        assertEquals("Shop", candidate.title);
        assertEquals("buy wearables", candidate.snippet);
        assertEquals("shop.example", candidate.displayedDomain);
        assertEquals("organic rank is preserved", 1, candidate.originalRank);
    }

    @Test
    public void budgetDenialReturnsEmptyWithoutCallingTheProvider() {
        FakeProvider provider = new FakeProvider();
        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("q", 10, null, null), CancellationSignal.NONE, DENY);
        assertTrue(result.candidates.isEmpty());
        assertEquals("provider must not be called when the budget is exhausted", null, provider.received);
    }

    @Test
    public void cancellationReturnsEmptyWithoutCallingTheProvider() {
        FakeProvider provider = new FakeProvider();
        CancellationSignal cancelled = new CancellationSignal() {
            public boolean isCancelled() {
                return true;
            }
        };
        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("q", 10, null, null), cancelled, ALLOW);
        assertTrue(result.candidates.isEmpty());
        assertEquals("provider must not be called after cancellation", null, provider.received);
    }
}
