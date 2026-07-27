package com.aresstack.askai.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structured, size-bounded search results (never raw HTML). */
public final class WebSearchResult {

    private final List<WebSearchItem> items;

    public WebSearchResult(List<WebSearchItem> items) {
        this.items = Collections.unmodifiableList(new ArrayList<WebSearchItem>(
                items == null ? Collections.<WebSearchItem>emptyList() : items));
    }

    public List<WebSearchItem> getItems() { return items; }
}
