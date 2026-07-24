package com.aresstack.askai.java8.hf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a HuggingFace model search: multi-library OR-merge (the server only ANDs repeated
 * {@code filter=} values) and the Base-only best-effort backfill loop. {@link HuggingFaceClient}
 * only speaks HTTP; this class owns the "how many requests, in what order" decisions so the client
 * stays a pure API-communication class (it must not decide what counts as a supported/complete
 * result set).
 */
public final class HuggingFaceSearchUseCase {

    /**
     * How many extra server pages Base-only is allowed to auto-fetch to backfill toward the
     * requested page size, since HuggingFace cannot filter "has any base_model relation" server-side
     * (it only matches a full {@code base_model:<relation>:<id>} tag, not a prefix). Bounded so an
     * unlucky, finetune-dominated query cannot trigger unbounded requests.
     */
    private static final int MAX_BASE_ONLY_EXTRA_PAGES = 4;

    private static final String MULTI_LIBRARY_NOTE =
            "Mehrere Libraries gleichzeitig zeigen aktuell nur die erste Seite je Library "
                    + "(zusammengeführt) — \"Load more\" folgt mit dem vollständigen Filterdialog.";

    private final HuggingFaceClient client;

    public HuggingFaceSearchUseCase(HuggingFaceClient client) {
        this.client = client;
    }

    /** Runs a fresh search (first page), discarding any prior pagination state. */
    public HuggingFaceSearchResult search(ModelSearchCriteria criteria) throws IOException {
        if (criteria.getLibraries().size() > 1) {
            return searchMergedLibraries(criteria);
        }
        HuggingFaceSearchPage page = client.searchModels(criteria);
        return applyBaseOnly(criteria, page, null);
    }

    /** Continues from a previous result's next-page URL; a no-op result when that isn't supported. */
    public HuggingFaceSearchResult loadMore(ModelSearchCriteria criteria, HuggingFaceSearchResult previous)
            throws IOException {
        if (!previous.isLoadMoreSupported() || previous.getNextPageUrl() == null) {
            return new HuggingFaceSearchResult(java.util.Collections.<HuggingFaceModel>emptyList(),
                    null, false, "Keine weiteren Treffer.");
        }
        HuggingFaceSearchPage page = client.loadMore(previous.getNextPageUrl());
        return applyBaseOnly(criteria, page, null);
    }

    /**
     * One request per selected library (HuggingFace ANDs repeated {@code filter=} values, so OR
     * across libraries needs separate requests), merged and de-duplicated by repository id. Phase 1
     * limitation: only the first page of each library is fetched — coherently paginating several
     * independently-cursored streams as one merged "load more" is deferred to the full filter dialog
     * (Phase 2), so this is flagged with a note rather than silently truncated.
     */
    private HuggingFaceSearchResult searchMergedLibraries(ModelSearchCriteria criteria) throws IOException {
        Map<String, HuggingFaceModel> merged = new LinkedHashMap<String, HuggingFaceModel>();
        List<String> libraries = criteria.getLibraries();
        for (int i = 0; i < libraries.size(); i++) {
            HuggingFaceSearchPage page = client.searchModels(criteria.withSingleLibrary(libraries.get(i)));
            List<HuggingFaceModel> models = page.getModels();
            for (int j = 0; j < models.size(); j++) {
                HuggingFaceModel model = models.get(j);
                if (!merged.containsKey(model.getId())) {
                    merged.put(model.getId(), model);
                }
            }
        }
        List<HuggingFaceModel> models = new ArrayList<HuggingFaceModel>(merged.values());
        if (criteria.isBaseOnly()) {
            models = filterBaseOnly(models);
        }
        return new HuggingFaceSearchResult(models, null, false, MULTI_LIBRARY_NOTE);
    }

    /**
     * When Base-only is off, passes the page through unchanged. When on, drops any hit with a
     * {@code base_model:} relation tag and, if that leaves fewer than the requested page size, keeps
     * following {@code nextPageUrl} (up to {@link #MAX_BASE_ONLY_EXTRA_PAGES} extra requests) to
     * backfill. Reports a shortfall note instead of silently returning a short page.
     */
    private HuggingFaceSearchResult applyBaseOnly(ModelSearchCriteria criteria, HuggingFaceSearchPage page,
                                                  String priorNote) throws IOException {
        if (!criteria.isBaseOnly()) {
            boolean hasMore = page.getNextPageUrl() != null;
            return new HuggingFaceSearchResult(page.getModels(), page.getNextPageUrl(), hasMore, priorNote);
        }
        List<HuggingFaceModel> filtered = new ArrayList<HuggingFaceModel>(filterBaseOnly(page.getModels()));
        String nextUrl = page.getNextPageUrl();
        int desired = criteria.getPageSize();
        int extraPages = 0;
        while (filtered.size() < desired && nextUrl != null && extraPages < MAX_BASE_ONLY_EXTRA_PAGES) {
            HuggingFaceSearchPage extra = client.loadMore(nextUrl);
            filtered.addAll(filterBaseOnly(extra.getModels()));
            nextUrl = extra.getNextPageUrl();
            extraPages++;
        }
        String note = priorNote;
        if (filtered.size() < desired) {
            String shortfall = "Base only: " + filtered.size() + " von " + desired + " angefragten Treffern "
                    + "gefunden" + (extraPages > 0 ? " (nach " + extraPages + " zusätzlichen Seiten)." : ".");
            note = note == null ? shortfall : note + " " + shortfall;
        }
        return new HuggingFaceSearchResult(filtered, nextUrl, nextUrl != null, note);
    }

    private static List<HuggingFaceModel> filterBaseOnly(List<HuggingFaceModel> models) {
        List<HuggingFaceModel> result = new ArrayList<HuggingFaceModel>();
        for (int i = 0; i < models.size(); i++) {
            HuggingFaceModel model = models.get(i);
            if (!model.hasBaseModelRelation()) {
                result.add(model);
            }
        }
        return result;
    }
}
