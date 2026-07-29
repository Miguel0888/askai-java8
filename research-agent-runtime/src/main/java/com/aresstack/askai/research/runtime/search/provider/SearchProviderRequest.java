package com.aresstack.askai.research.runtime.search.provider;

/**
 * The typed request handed to a single {@link SearchProvider}. Unlike the neutral
 * {@link com.aresstack.askai.research.runtime.search.InitialSearchRequest}, it names the concrete
 * {@link SearchEngine} the strategy chose, so a multi-engine provider (Bright Data, DataForSEO) knows which
 * index to query. {@code language}/{@code country} are optional overrides for the provider's configured
 * defaults.
 */
public final class SearchProviderRequest {

    private final String query;
    private final SearchEngine searchEngine;
    private final int requestedResultCount;
    private final String language;
    private final String country;

    public SearchProviderRequest(String query, SearchEngine searchEngine, int requestedResultCount,
                                 String language, String country) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query must not be empty");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("Search engine must not be null");
        }
        if (requestedResultCount <= 0) {
            throw new IllegalArgumentException("Requested result count must be positive");
        }
        this.query = query.trim();
        this.searchEngine = searchEngine;
        this.requestedResultCount = requestedResultCount;
        this.language = language;
        this.country = country;
    }

    public String getQuery() {
        return query;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public int getRequestedResultCount() {
        return requestedResultCount;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }
}
