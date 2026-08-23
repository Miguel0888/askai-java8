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

    public BrowserSearchEngine(String id, String displayName, List<String> endpointTemplates) {
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

    @Override
    public String toString() {
        return displayName + endpointTemplates;
    }
}
