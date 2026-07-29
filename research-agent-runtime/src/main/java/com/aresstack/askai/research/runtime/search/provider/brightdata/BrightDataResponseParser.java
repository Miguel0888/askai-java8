package com.aresstack.askai.research.runtime.search.provider.brightdata;

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
 * Parses Bright Data's SERP JSON (the payload produced by {@code brd_json=1}, returned verbatim by the
 * Direct API with {@code format=raw}). Only the {@code organic} array is read; each entry's {@code link} is
 * already a DIRECT target URL (not a search-engine redirect), so ads/paid blocks, People-Also-Ask, related
 * searches and knowledge panels never appear here. Entries whose host is the queried engine itself or a
 * Bright Data proxy host are dropped as transit, and {@code rank}/{@code global_rank} map to the organic
 * rank/diagnostic absolute rank. An empty {@code organic} array is a valid, successful empty result; a
 * missing {@code organic} key or a Bright Data {@code error} field is a typed response failure.
 *
 * <p>Assumed brd_json organic shape: {@code {link, title, description, rank, global_rank, display_link}}.</p>
 */
final class BrightDataResponseParser {

    private static final SearchProviderId PROVIDER = SearchProviderId.BRIGHT_DATA;

    private BrightDataResponseParser() {
    }

    static SearchProviderResult parse(SearchEngine actualEngine, String query, String body) {
        Object root;
        try {
            root = SearchJson.parse(body);
        } catch (SearchJson.JsonParseException ex) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Bright Data response is not valid JSON: " + ex.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Bright Data response root is not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) root;

        Object error = object.get("error");
        if (error instanceof String && !((String) error).trim().isEmpty()) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Bright Data reported an error: " + error);
        }

        Object organicValue = object.get("organic");
        if (!(organicValue instanceof List)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "Bright Data response has no 'organic' array");
        }

        List<SearchHit> hits = new ArrayList<SearchHit>();
        for (Object element : (List<?>) organicValue) {
            if (!(element instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            SearchHit hit = toHit(actualEngine, query, (Map<String, Object>) element);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return new SearchProviderResult(PROVIDER, actualEngine, hits);
    }

    private static SearchHit toHit(SearchEngine actualEngine, String query, Map<String, Object> item) {
        String link = string(item.get("link"));
        String title = string(item.get("title"));
        if (link == null || link.trim().isEmpty() || title == null || title.trim().isEmpty()) {
            return null;
        }
        String host = hostOf(link);
        if (isTransitHost(host)) {
            // A search-engine or Bright Data proxy URL is never a research target.
            return null;
        }
        String snippet = string(item.get("description"));
        int rank = intValue(item.get("rank"), 0);
        int globalRank = intValue(item.get("global_rank"), 0);
        return new SearchHit(PROVIDER, actualEngine, query, rank, link, title, snippet, host, globalRank,
                null);
    }

    /** Exclude the engine's own domain and known Bright Data proxy/infra hosts. */
    private static boolean isTransitHost(String host) {
        if (host.isEmpty()) {
            return true;
        }
        return host.endsWith("google.com")
                || host.endsWith("bing.com")
                || host.endsWith("yandex.com")
                || host.endsWith("yandex.ru")
                || host.endsWith("baidu.com")
                || host.endsWith("brightdata.com")
                || host.endsWith("superproxy.io")
                || host.endsWith("brd.superproxy.io");
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

    private static int intValue(Object value, int fallback) {
        return value instanceof Double ? (int) (double) (Double) value : fallback;
    }
}
