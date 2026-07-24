package com.aresstack.askai.java8.hf;

import java.util.Collections;
import java.util.List;

/**
 * The use-case-level result of a search or load-more step: the hits, whether "load more" can
 * continue from here, and an optional human-readable note (e.g. a Base-only shortfall, or that
 * pagination isn't available yet for a multi-library merge) for the UI to surface in its log.
 */
public final class HuggingFaceSearchResult {

    private final List<HuggingFaceModel> models;
    private final String nextPageUrl;
    private final boolean loadMoreSupported;
    private final String note;

    public HuggingFaceSearchResult(List<HuggingFaceModel> models, String nextPageUrl,
                                   boolean loadMoreSupported, String note) {
        this.models = models == null ? Collections.<HuggingFaceModel>emptyList() : models;
        this.nextPageUrl = nextPageUrl;
        this.loadMoreSupported = loadMoreSupported;
        this.note = note;
    }

    public List<HuggingFaceModel> getModels() {
        return models;
    }

    public String getNextPageUrl() {
        return nextPageUrl;
    }

    public boolean isLoadMoreSupported() {
        return loadMoreSupported;
    }

    /** @return an optional note for the UI log (shortfall/limitation explanation), or {@code null}. */
    public String getNote() {
        return note;
    }
}
