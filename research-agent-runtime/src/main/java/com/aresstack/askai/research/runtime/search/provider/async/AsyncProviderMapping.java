package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

/**
 * Pure mapping between the runtime search type-space
 * ({@link SearchProviderId}/{@link SearchEngine}) and the async module's own
 * ({@link com.aresstack.askai.research.search.api.SearchProviderId}/{@link
 * com.aresstack.askai.research.search.api.SearchEngine}). Only the three migrated providers map; anything
 * else (e.g. the module's {@code COMPOSITE}) returns {@code null} so the caller can skip it.
 */
public final class AsyncProviderMapping {

    private AsyncProviderMapping() {
    }

    /** The runtime id for a module provider id, or {@code null} when it has no runtime counterpart. */
    public static SearchProviderId toRuntimeId(
            com.aresstack.askai.research.search.api.SearchProviderId moduleId) {
        if (moduleId == null) {
            return null;
        }
        switch (moduleId) {
            case BRAVE:
                return SearchProviderId.BRAVE_SEARCH_API;
            case BRIGHT_DATA:
                return SearchProviderId.BRIGHT_DATA;
            case DATA_FOR_SEO:
                return SearchProviderId.DATA_FOR_SEO;
            default:
                return null; // COMPOSITE and any future module-only ids are not wired into the runtime seam
        }
    }

    /** The search engine a migrated provider labels its results with, or {@code null} if not migrated. */
    public static SearchEngine defaultEngine(SearchProviderId runtimeId) {
        if (runtimeId == null) {
            return null;
        }
        switch (runtimeId) {
            case BRAVE_SEARCH_API:
                return SearchEngine.BRAVE;
            case BRIGHT_DATA:
            case DATA_FOR_SEO:
                return SearchEngine.GOOGLE; // both are Google SERP APIs
            default:
                return null;
        }
    }
}
