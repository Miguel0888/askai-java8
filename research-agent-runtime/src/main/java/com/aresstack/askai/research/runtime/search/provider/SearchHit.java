package com.aresstack.askai.research.runtime.search.provider;

/**
 * One organic result as a provider reports it — NOT yet a research source and not yet a normalized URL. A
 * hit records the provider and the actual {@link SearchEngine} that produced it (so multi-provider origin
 * and cross-engine agreement can be reasoned about later), plus the provider's own organic {@link #rank}
 * and the diagnostic {@link #absoluteRank}/{@link #domain}/{@link #publishedAt} metadata. A {@link SearchHit}
 * must never be stored directly as a source: only Playwright capture and source acceptance may do that.
 */
public final class SearchHit {

    private final SearchProviderId providerId;
    private final SearchEngine searchEngine;
    private final String query;
    /** 1-based organic rank within the organic result group (DataForSEO {@code rank_group}). */
    private final int rank;
    private final String url;
    private final String title;
    private final String snippet;
    private final String domain;
    /** Absolute SERP rank across all element types — diagnostics only (DataForSEO {@code rank_absolute}). */
    private final int absoluteRank;
    /** Optional provider-reported timestamp, or {@code null}. */
    private final String publishedAt;

    public SearchHit(SearchProviderId providerId, SearchEngine searchEngine, String query, int rank,
                     String url, String title, String snippet, String domain, int absoluteRank,
                     String publishedAt) {
        if (providerId == null) {
            throw new IllegalArgumentException("Provider id must not be null");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("Search engine must not be null");
        }
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Search result URL must not be empty");
        }
        this.providerId = providerId;
        this.searchEngine = searchEngine;
        this.query = query;
        this.rank = rank;
        this.url = url.trim();
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.domain = domain == null ? "" : domain;
        this.absoluteRank = absoluteRank;
        this.publishedAt = publishedAt;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public String getQuery() {
        return query;
    }

    public int getRank() {
        return rank;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getDomain() {
        return domain;
    }

    public int getAbsoluteRank() {
        return absoluteRank;
    }

    public String getPublishedAt() {
        return publishedAt;
    }
}
