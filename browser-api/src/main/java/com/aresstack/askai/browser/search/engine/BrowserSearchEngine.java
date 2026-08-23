package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ONE search engine as the user knows it — "DuckDuckGo", not "html.duckduckgo.com" and
 * "lite.duckduckgo.com". The technical endpoints are the engine's own business: they are alternative ways
 * to ask the SAME provider, tried in order until one answers, and a user ordering providers should never
 * have to order their transports.
 */
public final class BrowserSearchEngine {

    private final String id;
    private final String displayName;
    private final List<String> endpointTemplates;
    /**
     * Per endpoint (same index): the template for RESULT PAGE 2..n, or "" when that endpoint cannot
     * page. Placeholders: {query}, {page} (1-based), {offset0} ((page-1)*resultsPerPage) and
     * {offset1} (offset0+1) — engines address follow-up pages differently, so the math stays DATA in
     * the catalog, never engine-specific code.
     */
    private final List<String> nextPageTemplates;
    /** How many organic results one page of this engine carries (drives the offset placeholders). */
    private final int resultsPerPage;

    public BrowserSearchEngine(String id, String displayName, List<String> endpointTemplates) {
        this(id, displayName, endpointTemplates, java.util.Collections.<String>emptyList(), 10);
    }

    public BrowserSearchEngine(String id, String displayName, List<String> endpointTemplates,
                               List<String> nextPageTemplates, int resultsPerPage) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("engine id must not be empty");
        }
        if (endpointTemplates == null || endpointTemplates.isEmpty()) {
            throw new IllegalArgumentException("engine " + id + " has no endpoint template");
        }
        this.id = id.trim();
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? this.id : displayName.trim();
        this.endpointTemplates = Collections.unmodifiableList(
                new ArrayList<String>(endpointTemplates));
        this.nextPageTemplates = Collections.unmodifiableList(new ArrayList<String>(
                nextPageTemplates == null ? Collections.<String>emptyList() : nextPageTemplates));
        this.resultsPerPage = resultsPerPage > 0 ? resultsPerPage : 10;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The engine's URL templates ({@code {query}} placeholder), in the order they are tried. */
    public List<String> getEndpointTemplates() {
        return endpointTemplates;
    }

    /**
     * The URL of result page {@code page} (1-based) for the endpoint at {@code endpointIndex}, with the
     * ALREADY-ENCODED query substituted — or {@code null} when this endpoint cannot deliver that page
     * (no pagination template). Page 1 is always the plain endpoint template.
     */
    public String pageUrl(int endpointIndex, String encodedQuery, int page) {
        if (endpointIndex < 0 || endpointIndex >= endpointTemplates.size()) {
            return null;
        }
        if (page <= 1) {
            return endpointTemplates.get(endpointIndex).replace("{query}", encodedQuery);
        }
        if (endpointIndex >= nextPageTemplates.size()
                || nextPageTemplates.get(endpointIndex).trim().isEmpty()) {
            return null;
        }
        int offset0 = (page - 1) * resultsPerPage;
        return nextPageTemplates.get(endpointIndex)
                .replace("{query}", encodedQuery)
                .replace("{page}", String.valueOf(page))
                .replace("{offset0}", String.valueOf(offset0))
                .replace("{offset1}", String.valueOf(offset0 + 1));
    }

    @Override
    public String toString() {
        return displayName + endpointTemplates;
    }
}
