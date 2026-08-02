package com.aresstack.askai.research.search;

/**
 * A user-triggered web search request. It carries ONLY the query on purpose: provider, language, country and
 * result count are owned by the existing research/search configuration, never duplicated by the Swing button
 * that raised the search (RA §5). Extend this with more fachlich-needed fields when a slice actually requires
 * them — not speculatively.
 */
public final class ManualWebSearchRequest {

    private final String query;

    public ManualWebSearchRequest(String query) {
        this.query = query == null ? "" : query.trim();
    }

    public String getQuery() {
        return query;
    }

    public boolean isBlank() {
        return query.isEmpty();
    }
}
