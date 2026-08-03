package com.aresstack.askai.research.search;

import com.aresstack.askai.research.agent.ResearchLanguage;

/**
 * A user-triggered web search request: the query plus an IMMUTABLE language snapshot taken when the user
 * raised the search — a session language switch during a running search never changes that search. Provider,
 * country and result count stay owned by the existing research/search configuration (RA §5); the request
 * language OVERRIDES the provider's default language, exactly like {@code WebSearchRequest} field semantics.
 */
public final class ManualWebSearchRequest {

    private final String query;
    private final ResearchLanguage language;

    /** English snapshot convenience (legacy callers/tests without a session language). */
    public ManualWebSearchRequest(String query) {
        this(query, null);
    }

    public ManualWebSearchRequest(String query, ResearchLanguage language) {
        this.query = query == null ? "" : query.trim();
        this.language = language == null ? ResearchLanguage.ENGLISH : language;
    }

    public String getQuery() {
        return query;
    }

    public ResearchLanguage getLanguage() {
        return language;
    }

    public boolean isBlank() {
        return query.isEmpty();
    }
}
