package com.aresstack.askai.browser.search.repair;

import com.aresstack.askai.browser.search.SearchResultCandidate;

import java.util.Collections;
import java.util.List;

/**
 * The typed result of {@code web_search_prepare}: either directly-extracted organic candidates, or —
 * on {@link WebSearchPreparationStatus#REPAIR_REQUIRED} — an ORDERED list of bounded repair requests,
 * one per low-confidence engine page, that the runtime works through until one yields a valid
 * extraction. A low-confidence page is never lost just because a later engine was also probed.
 */
public final class PreparedWebSearchResult {

    public final WebSearchPreparationStatus status;
    public final List<SearchResultCandidate> candidates;
    public final List<SearchLayoutRepairRequest> repairRequests;
    public final List<String> diagnostics;

    public PreparedWebSearchResult(WebSearchPreparationStatus status,
                                   List<SearchResultCandidate> candidates,
                                   List<SearchLayoutRepairRequest> repairRequests,
                                   List<String> diagnostics) {
        this.status = status == null ? WebSearchPreparationStatus.FAILED : status;
        this.candidates = unmodifiable(candidates);
        this.repairRequests = repairRequests == null
                ? Collections.<SearchLayoutRepairRequest>emptyList()
                : Collections.unmodifiableList(repairRequests);
        this.diagnostics = diagnosticsCopy(diagnostics);
    }

    private static List<SearchResultCandidate> unmodifiable(List<SearchResultCandidate> value) {
        return value == null
                ? Collections.<SearchResultCandidate>emptyList()
                : Collections.unmodifiableList(value);
    }

    private static List<String> diagnosticsCopy(List<String> value) {
        return value == null ? Collections.<String>emptyList() : Collections.unmodifiableList(value);
    }
}
