package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The transport-agnostic handler logic behind the three MCP repair tools — String in, String out, so
 * the SAME code serves the productive Java-21 sidecar registration and the cross-process integration
 * test. It stays MODEL-FREE: it captures/extracts mechanically and applies a runtime-validated
 * decision to a cached snapshot; it never calls a model. The clock is injected so the sidecar owns
 * time and tests stay deterministic.
 */
public final class SearchLayoutRepairTools {

    private final WebSearchLayoutRepairService service;
    private final RenderedPageSource pageSource;
    private final LongSupplier clock;

    public SearchLayoutRepairTools(LegacyBrowserSearchSettings settings, RenderedPageSource pageSource,
                                   int maximumTickets, long ticketTtlMillis, LongSupplier clock) {
        this.service = new WebSearchLayoutRepairService(settings, maximumTickets, ticketTtlMillis);
        this.pageSource = pageSource;
        this.clock = clock;
    }

    /** {@code web_search_prepare(query)} → encoded {@link PreparedWebSearchResult}. */
    public String prepare(String query) {
        long now = clock.getAsLong();
        RenderedPageSource.EngineCapture capture = pageSource.capture(query);
        List<PreparedWebSearchResult> perEngine = new ArrayList<PreparedWebSearchResult>();
        for (RenderedPageSource.Captured captured : capture.pages) {
            perEngine.add(service.prepareSingle(captured.document, query, captured.engineHost, now));
        }
        PreparedWebSearchResult merged = WebSearchLayoutRepairService.merge(perEngine);
        // Carry the navigation metadata (provider hosts, per-engine attempts, challenges) so the
        // research loop keeps the full legacy web_search behaviour.
        PreparedWebSearchResult withMetadata = new PreparedWebSearchResult(merged.status,
                merged.candidates, merged.repairRequests, capture.providerHosts,
                capture.engineAttempts, capture.challenges, merged.diagnostics);
        return SearchLayoutRepairJson.encodePrepared(withMetadata);
    }

    /** {@code web_search_apply_layout(submission)} → encoded {@link SearchLayoutRepairResult}. */
    public String applyLayout(String submissionJson) {
        SearchLayoutRepairSubmission submission;
        try {
            submission = SearchLayoutRepairJson.decodeSubmission(submissionJson);
        } catch (SearchLayoutRepairJson.DecodeException bad) {
            return SearchLayoutRepairJson.encodeRepairResult(new SearchLayoutRepairResult(
                    SearchLayoutRepairStatus.INVALID_DECISION,
                    Collections.<com.aresstack.askai.browser.search.SearchResultCandidate>emptyList(),
                    Collections.singletonList("undecodable submission: " + bad.getMessage())));
        }
        return SearchLayoutRepairJson.encodeRepairResult(service.apply(submission, clock.getAsLong()));
    }

    /** {@code web_search_discard_repair(repairTicketId)} → a short status line. */
    public String discard(String repairTicketId) {
        service.discard(new com.aresstack.askai.browser.search.repair
                .SearchLayoutRepairAttemptId(repairTicketId));
        return "DISCARDED " + repairTicketId;
    }

    /** Cleanup for session close / browser recovery / shutdown. */
    public void clear() {
        service.clear();
    }
}
