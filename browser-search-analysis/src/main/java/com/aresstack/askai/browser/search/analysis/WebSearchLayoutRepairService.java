package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisDiagnosticArtifact;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The MODEL-FREE sidecar core of the repair bridge (A4 productive). It never calls a model: it
 * captures nothing itself, but given a rendered page it either extracts organic candidates
 * mechanically (HIGH_CONFIDENCE) or, for a LOW_CONFIDENCE layout, emits a bounded
 * {@link SearchLayoutRepairRequest} and holds the {@code RenderedPageDocument} in a bounded, one-shot
 * {@link SearchLayoutRepairCache}. Later it applies a VALIDATED decision the runtime returns — after
 * re-checking the attempt, snapshot, fingerprint and container ids — through the single A3/A4
 * extraction. Time is injected so the sidecar owns the clock.
 */
public final class WebSearchLayoutRepairService {

    private final LegacyBrowserSearchSettings settings;
    private final SearchPageMechanicalAnalyzer analyzer;
    private final LegacySearchResultExtractor extractor;
    private final SearchPageAnalysisArtifactBuilder artifactBuilder;
    private final SearchPageAnalysisDiagnosticArtifactBuilder diagnosticBuilder;
    private final SearchLayoutRepairCache cache;

    public WebSearchLayoutRepairService(LegacyBrowserSearchSettings settings, int maximumAttempts,
                                        long ttlMillis) {
        this.settings = settings;
        this.analyzer = new SearchPageMechanicalAnalyzer(settings);
        this.extractor = new LegacySearchResultExtractor(settings); // model-free: no resolver
        this.artifactBuilder = new SearchPageAnalysisArtifactBuilder(settings);
        this.diagnosticBuilder = new SearchPageAnalysisDiagnosticArtifactBuilder(settings.diagnostics);
        this.cache = new SearchLayoutRepairCache(maximumAttempts, ttlMillis);
    }

    public SearchLayoutRepairCache cache() {
        return cache;
    }

    /**
     * Prepare one captured engine page: HIGH_CONFIDENCE yields organic candidates directly; an
     * explicit no-results page yields NO_ORGANIC_RESULTS; a LOW_CONFIDENCE layout yields a single
     * bounded repair request and caches the document for later application.
     */
    public PreparedWebSearchResult prepareSingle(RenderedPageDocument document, String query,
                                                 String engineHost, long nowEpochMillis) {
        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        SearchResultExtractionResult extraction = extractor.extract(document, query);

        if (!resolution.lowConfidence) {
            WebSearchPreparationStatus status = statusOf(extraction.outcome);
            return new PreparedWebSearchResult(status, extraction.candidates,
                    Collections.<SearchLayoutRepairRequest>emptyList(), extraction.diagnostics);
        }
        if (extraction.outcome == SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS) {
            return new PreparedWebSearchResult(WebSearchPreparationStatus.NO_ORGANIC_RESULTS,
                    Collections.<SearchResultCandidate>emptyList(),
                    Collections.<SearchLayoutRepairRequest>emptyList(), extraction.diagnostics);
        }

        SearchPageAnalysisArtifact artifact = artifactBuilder.build(document, resolution, query);
        SearchPageAnalysisDiagnosticArtifact diagnostics =
                diagnosticBuilder.build(artifact, null, "NONE", "REPAIR_REQUIRED");
        String attemptId = "repair-" + document.snapshotId;
        SearchLayoutRepairCache.Entry entry =
                cache.put(attemptId, document, query, engineHost, nowEpochMillis);
        SearchLayoutRepairRequest request = new SearchLayoutRepairRequest(
                new SearchLayoutRepairAttemptId(attemptId), query, engineHost, artifact.engineFamily,
                document.snapshotId, document.snapshotGeneration,
                document.documentFingerprint == null ? "" : document.documentFingerprint.value,
                artifact, diagnostics, nowEpochMillis, entry.expiresAtEpochMillis);
        List<SearchLayoutRepairRequest> requests = Collections.singletonList(request);
        return new PreparedWebSearchResult(WebSearchPreparationStatus.REPAIR_REQUIRED,
                Collections.<SearchResultCandidate>emptyList(), requests, extraction.diagnostics);
    }

    /**
     * Apply a runtime-validated decision to the cached snapshot. Every guard is re-checked in the
     * sidecar; only then is the EXISTING extraction run. The attempt is consumed exactly once, the
     * moment the extraction is actually attempted.
     */
    public SearchLayoutRepairResult apply(SearchLayoutRepairSubmission submission,
                                          long nowEpochMillis) {
        String attemptId = submission.attemptId.value;
        SearchLayoutRepairCache.Entry entry = cache.find(attemptId);
        if (entry == null) {
            return rejected(SearchLayoutRepairStatus.UNKNOWN_ATTEMPT, "unknown attempt " + attemptId);
        }
        if (cache.isConsumed(entry)) {
            return rejected(SearchLayoutRepairStatus.ALREADY_CONSUMED,
                    "attempt already consumed " + attemptId);
        }
        if (cache.isExpired(entry, nowEpochMillis)) {
            cache.discard(attemptId);
            return rejected(SearchLayoutRepairStatus.EXPIRED_ATTEMPT, "attempt expired " + attemptId);
        }
        if (!entry.document.snapshotId.equals(submission.snapshotId)) {
            return rejected(SearchLayoutRepairStatus.SNAPSHOT_MISMATCH,
                    "snapshot mismatch for " + attemptId);
        }
        String cachedFingerprint = entry.document.documentFingerprint == null
                ? "" : entry.document.documentFingerprint.value;
        if (!cachedFingerprint.equals(submission.documentFingerprint)) {
            return rejected(SearchLayoutRepairStatus.FINGERPRINT_MISMATCH,
                    "fingerprint mismatch for " + attemptId);
        }
        if (submission.decision == null
                || !entry.document.snapshotId.equals(submission.decision.snapshotId)
                || submission.decision.primaryOrganicContainerId.isEmpty()
                || entry.document.container(submission.decision.primaryOrganicContainerId) == null) {
            return rejected(SearchLayoutRepairStatus.INVALID_DECISION,
                    "invalid or non-resolving decision for " + attemptId);
        }

        SearchResultExtractionResult extraction =
                extractor.extract(entry.document, submission.decision);
        cache.consume(attemptId); // one-shot: consumed whether or not blocks emerged
        SearchLayoutRepairStatus status =
                extraction.outcome == SearchPageAnalysisOutcome.ORGANIC_RESULTS
                        ? SearchLayoutRepairStatus.ORGANIC_RESULTS
                        : SearchLayoutRepairStatus.EXTRACTION_FAILED;
        return new SearchLayoutRepairResult(status, extraction.candidates, extraction.diagnostics);
    }

    public void discard(SearchLayoutRepairAttemptId attemptId) {
        cache.discard(attemptId.value);
    }

    /** Cleanup on session close / browser recovery. */
    public void clear() {
        cache.clear();
    }

    /**
     * Merge per-engine prepared results in engine order: a direct organic hit wins; otherwise all
     * repair requests are offered in order; otherwise an explicit empty is reported; else FAILED.
     */
    public static PreparedWebSearchResult merge(List<PreparedWebSearchResult> perEngine) {
        List<SearchLayoutRepairRequest> repairs = new ArrayList<SearchLayoutRepairRequest>();
        List<String> diagnostics = new ArrayList<String>();
        boolean sawNoResults = false;
        for (PreparedWebSearchResult result : perEngine) {
            if (result == null) {
                continue;
            }
            diagnostics.addAll(result.diagnostics);
            if (result.status == WebSearchPreparationStatus.ORGANIC_RESULTS) {
                return new PreparedWebSearchResult(WebSearchPreparationStatus.ORGANIC_RESULTS,
                        result.candidates, Collections.<SearchLayoutRepairRequest>emptyList(),
                        diagnostics);
            }
            repairs.addAll(result.repairRequests);
            if (result.status == WebSearchPreparationStatus.NO_ORGANIC_RESULTS) {
                sawNoResults = true;
            }
        }
        if (!repairs.isEmpty()) {
            return new PreparedWebSearchResult(WebSearchPreparationStatus.REPAIR_REQUIRED,
                    Collections.<SearchResultCandidate>emptyList(), repairs, diagnostics);
        }
        WebSearchPreparationStatus status = sawNoResults
                ? WebSearchPreparationStatus.NO_ORGANIC_RESULTS : WebSearchPreparationStatus.FAILED;
        return new PreparedWebSearchResult(status,
                Collections.<SearchResultCandidate>emptyList(),
                Collections.<SearchLayoutRepairRequest>emptyList(), diagnostics);
    }

    private static WebSearchPreparationStatus statusOf(SearchPageAnalysisOutcome outcome) {
        switch (outcome) {
            case ORGANIC_RESULTS:
                return WebSearchPreparationStatus.ORGANIC_RESULTS;
            case NO_ORGANIC_RESULTS:
                return WebSearchPreparationStatus.NO_ORGANIC_RESULTS;
            case EXTRACTION_FAILED:
            default:
                return WebSearchPreparationStatus.FAILED;
        }
    }

    private static SearchLayoutRepairResult rejected(SearchLayoutRepairStatus status, String reason) {
        return new SearchLayoutRepairResult(status,
                Collections.<SearchResultCandidate>emptyList(), Collections.singletonList(reason));
    }
}
