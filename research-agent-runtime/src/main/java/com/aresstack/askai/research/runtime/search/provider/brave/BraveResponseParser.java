package com.aresstack.askai.research.runtime.search.provider.brave;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchJson;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResponseException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a Brave Search API {@code /res/v1/web/search} JSON response. Only the normal web results
 * ({@code web.results[]}) are read — the {@code news}, {@code videos}, {@code faq}, {@code infobox} and
 * {@code mixed} blocks are ignored — and each result's {@code url} is already a DIRECT target URL. Rank is
 * the 1-based position in the web results (Brave carries no per-item rank field). The engine is always
 * {@link SearchEngine#BRAVE}. An absent {@code web} block is a valid empty result; a body that is not a JSON
 * object, or a malformed {@code web}/{@code results} shape, is a typed response failure.
 *
 * <p>Assumed item shape: {@code {title, url, description, meta_url:{hostname}, page_age}}.</p>
 */
final class BraveResponseParser {

    private static final SearchProviderId PROVIDER = SearchProviderId.BRAVE_SEARCH_API;

    private BraveResponseParser() {
    }

    static SearchProviderResult parse(String query, String body) {
        Object root;
        try {
            root = SearchJson.parse(body);
        } catch (SearchJson.JsonParseException ex) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Brave response is not valid JSON: " + ex.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Brave response root is not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) root;

        List<SearchHit> hits = new ArrayList<SearchHit>();
        Object webValue = object.get("web");
        if (webValue == null) {
            // A successful search with no web block (e.g. zero web results) is a valid empty result.
            return new SearchProviderResult(PROVIDER, SearchEngine.BRAVE, hits);
        }
        if (!(webValue instanceof Map)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Brave response 'web' is not an object");
        }
        Object resultsValue = ((Map<?, ?>) webValue).get("results");
        if (resultsValue == null) {
            return new SearchProviderResult(PROVIDER, SearchEngine.BRAVE, hits);
        }
        if (!(resultsValue instanceof List)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Brave response 'web.results' is not an array");
        }

        int rank = 0;
        for (Object element : (List<?>) resultsValue) {
            if (!(element instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) element;
            rank++;
            SearchHit hit = toHit(query, item, rank);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return new SearchProviderResult(PROVIDER, SearchEngine.BRAVE, hits);
    }

    private static SearchHit toHit(String query, Map<String, Object> item, int rank) {
        String url = string(item.get("url"));
        String title = string(item.get("title"));
        if (url == null || url.trim().isEmpty() || title == null || title.trim().isEmpty()) {
            return null;
        }
        String snippet = string(item.get("description"));
        String domain = metaHostname(item.get("meta_url"));
        if (domain == null) {
            domain = hostOf(url);
        }
        String publishedAt = string(item.get("page_age"));
        // Brave has no interleaved absolute rank; the organic position is the only rank.
        return new SearchHit(PROVIDER, SearchEngine.BRAVE, query, rank, url, title, snippet, domain, rank,
                publishedAt);
    }

    private static String metaHostname(Object metaUrl) {
        if (metaUrl instanceof Map) {
            return string(((Map<?, ?>) metaUrl).get("hostname"));
        }
        return null;
    }

    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int at = authority.indexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.toLowerCase(Locale.ROOT);
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
