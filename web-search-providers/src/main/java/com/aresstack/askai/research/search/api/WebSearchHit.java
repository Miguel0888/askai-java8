package com.aresstack.askai.research.search.api;

public final class WebSearchHit {

    private final SearchProviderId providerId;
    private final SearchEngine searchEngine;
    private final int rank;
    private final String title;
    private final String url;
    private final String snippet;

    public WebSearchHit(
            SearchProviderId providerId,
            SearchEngine searchEngine,
            int rank,
            String title,
            String url,
            String snippet) {

        this.providerId = requireNonNull(providerId, "providerId");
        this.searchEngine = requireNonNull(searchEngine, "searchEngine");
        this.rank = requirePositive(rank, "rank");
        this.title = requireText(title, "title");
        this.url = requireText(url, "url");
        this.snippet = snippet == null ? "" : snippet;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public int getRank() {
        return rank;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getSnippet() {
        return snippet;
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }

    private static String requireText(
            String value,
            String propertyName) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    propertyName + " must not be empty");
        }
        return value;
    }

    private static int requirePositive(
            int value,
            String propertyName) {

        if (value <= 0) {
            throw new IllegalArgumentException(
                    propertyName + " must be positive");
        }
        return value;
    }
}
