package com.aresstack.askai.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured, size-bounded search results (never raw HTML). {@code providerHosts} are the hosts of every
 * search-engine page visited to produce these results (a fallback engine adds a second host) — callers use
 * them to treat the search engines themselves as transit, never as an evidence source.
 */
public final class WebSearchResult {

    private final List<WebSearchItem> items;
    private final List<String> providerHosts;

    public WebSearchResult(List<WebSearchItem> items) {
        this(items, Collections.<String>emptyList());
    }

    public WebSearchResult(List<WebSearchItem> items, String providerHost) {
        this(items, providerHost == null || providerHost.isEmpty()
                ? Collections.<String>emptyList() : Collections.singletonList(providerHost));
    }

    public WebSearchResult(List<WebSearchItem> items, List<String> providerHosts) {
        this.items = Collections.unmodifiableList(new ArrayList<WebSearchItem>(
                items == null ? Collections.<WebSearchItem>emptyList() : items));
        this.providerHosts = Collections.unmodifiableList(new ArrayList<String>(
                providerHosts == null ? Collections.<String>emptyList() : providerHosts));
    }

    public List<WebSearchItem> getItems() { return items; }

    /** Hosts of all search pages used for this result (empty when unknown). */
    public List<String> getProviderHosts() { return providerHosts; }

    /** First provider host ("" when unknown) — convenience for single-engine callers. */
    public String getProviderHost() { return providerHosts.isEmpty() ? "" : providerHosts.get(0); }
}
