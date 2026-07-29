package com.aresstack.askai.research.runtime.search.provider.dataforseo;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchJson;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRateLimitException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResponseException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderTemporaryException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the PRODUCTIVE DataForSEO {@code serp/.../organic/live/advanced} response envelope — the general
 * response object with a {@code tasks} array, each task carrying its own {@code status_code} and a
 * {@code result[].items[]} list — not just the single task object visible in the API playground. Only items
 * with {@code type == "organic"}, a non-empty URL and title, and {@code is_malicious != true} become
 * {@link SearchHit}s. {@code rank_group} is the organic rank; {@code rank_absolute} is kept as diagnostics;
 * {@code check_url} and all non-organic elements (products, PAA, video, related searches, knowledge graph,
 * paid, shopping) are ignored. An empty result set is a valid, successful outcome — never a failure.
 */
final class DataForSeoResponseParser {

    private static final SearchProviderId PROVIDER = SearchProviderId.DATA_FOR_SEO;
    private static final double OK = 20000d;

    private DataForSeoResponseParser() {
    }

    static SearchProviderResult parse(SearchEngine actualEngine, String query, String body) {
        Object root;
        try {
            root = SearchJson.parse(body);
        } catch (SearchJson.JsonParseException ex) {
            throw new SearchProviderResponseException(PROVIDER,
                    "DataForSEO response is not valid JSON: " + ex.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "DataForSEO response root is not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) root;

        checkStatus(number(object.get("status_code")), string(object.get("status_message")),
                "response");

        Object tasksValue = object.get("tasks");
        if (!(tasksValue instanceof List)) {
            throw new SearchProviderResponseException(PROVIDER,
                    "DataForSEO response has no 'tasks' array");
        }
        List<?> tasks = (List<?>) tasksValue;
        if (tasks.isEmpty()) {
            throw new SearchProviderResponseException(PROVIDER,
                    "DataForSEO response 'tasks' array is empty");
        }
        if (!(tasks.get(0) instanceof Map)) {
            throw new SearchProviderResponseException(PROVIDER, "DataForSEO task entry is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) tasks.get(0);

        checkStatus(number(task.get("status_code")), string(task.get("status_message")), "task");

        List<SearchHit> hits = new ArrayList<SearchHit>();
        Object resultValue = task.get("result");
        if (resultValue instanceof List) {
            for (Object resultElement : (List<?>) resultValue) {
                if (!(resultElement instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) resultElement;
                Object itemsValue = result.get("items");
                if (!(itemsValue instanceof List)) {
                    continue;
                }
                for (Object itemElement : (List<?>) itemsValue) {
                    if (!(itemElement instanceof Map)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) itemElement;
                    SearchHit hit = toHit(actualEngine, query, item);
                    if (hit != null) {
                        hits.add(hit);
                    }
                }
            }
        }
        return new SearchProviderResult(PROVIDER, actualEngine, hits);
    }

    /** Verbindliche Filter: type==organic, url + title non-empty, is_malicious != true. */
    private static SearchHit toHit(SearchEngine actualEngine, String query, Map<String, Object> item) {
        if (!"organic".equals(string(item.get("type")))) {
            return null;
        }
        Object malicious = item.get("is_malicious");
        if (malicious instanceof Boolean && (Boolean) malicious) {
            return null;
        }
        String url = string(item.get("url"));
        String title = string(item.get("title"));
        if (url == null || url.trim().isEmpty() || title == null || title.trim().isEmpty()) {
            return null;
        }
        String snippet = string(item.get("description"));
        String domain = string(item.get("domain"));
        int rankGroup = intValue(item.get("rank_group"), 0);
        int rankAbsolute = intValue(item.get("rank_absolute"), 0);
        String timestamp = string(item.get("timestamp")); // check_url is diagnostics only — never a target
        return new SearchHit(PROVIDER, actualEngine, query, rankGroup, url, title, snippet, domain,
                rankAbsolute, timestamp);
    }

    /** Map DataForSEO status codes onto typed provider exceptions; 20000 (and null, tolerated) pass. */
    private static void checkStatus(Double statusCode, String statusMessage, String scope) {
        if (statusCode == null || statusCode == OK) {
            return;
        }
        int code = (int) (double) statusCode;
        String detail = "DataForSEO " + scope + " status " + code
                + (statusMessage == null ? "" : " (" + statusMessage + ")");
        if (code >= 40100 && code < 40200) {
            throw new SearchProviderAuthenticationException(PROVIDER, detail);
        }
        if (code >= 40200 && code < 40300) {
            throw new SearchProviderConfigurationException(PROVIDER, detail);
        }
        if (code == 40402 || code == 40429 || code == 42900) {
            throw new SearchProviderRateLimitException(PROVIDER, detail);
        }
        if (code >= 50000 && code < 60000) {
            throw new SearchProviderTemporaryException(PROVIDER, detail);
        }
        throw new SearchProviderResponseException(PROVIDER, detail);
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Double number(Object value) {
        return value instanceof Double ? (Double) value : null;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Double ? (int) (double) (Double) value : fallback;
    }
}
