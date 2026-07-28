package com.aresstack.askai.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured, size-bounded search results (never raw HTML). {@code providerHost} is the host of the FINAL
 * loaded search page — callers use it to treat the search engine itself as transit, never as an evidence
 * source or a link farm.
 */
public final class WebSearchResult {

    private final List<WebSearchItem> items;
    private final String providerHost;

    public WebSearchResult(List<WebSearchItem> items) {
        this(items, "");
    }

    public WebSearchResult(List<WebSearchItem> items, String providerHost) {
        this.items = Collections.unmodifiableList(new ArrayList<WebSearchItem>(
                items == null ? Collections.<WebSearchItem>emptyList() : items));
        this.providerHost = providerHost == null ? "" : providerHost;
    }

    public List<WebSearchItem> getItems() { return items; }

    /** Host of the final search page ("" when unknown). */
    public String getProviderHost() { return providerHost; }
}
