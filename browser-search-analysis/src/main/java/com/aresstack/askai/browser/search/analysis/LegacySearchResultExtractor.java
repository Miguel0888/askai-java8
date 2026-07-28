package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.SearchResultSiteLink;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The full mechanical extraction pipeline (A3d): rendered document → layout resolution → result
 * blocks → typed candidates, with HONEST outcome semantics:
 * <ul>
 * <li>{@code ORGANIC_RESULTS} — validated result blocks were extracted,</li>
 * <li>{@code NO_ORGANIC_RESULTS} — a valid SERP or an EXPLICIT no-results indication with
 *     genuinely zero hits (the configured no-results texts decide),</li>
 * <li>{@code EXTRACTION_FAILED} — no plausible result region, contradictory structure or low
 *     confidence. An ununderstood layout is NEVER reported as an empty engine.</li>
 * </ul>
 * LOW_CONFIDENCE does not call any AI in A3 — it yields EXTRACTION_FAILED and the existing
 * engine-fallback policy moves on; the AI layout resolver plugs in exactly here later.
 */
public final class LegacySearchResultExtractor {

    private final LegacyBrowserSearchSettings settings;
    private final SearchPageMechanicalAnalyzer analyzer;
    private final SearchResultBlockDetector blockDetector;

    public LegacySearchResultExtractor(LegacyBrowserSearchSettings settings) {
        this.settings = settings;
        this.analyzer = new SearchPageMechanicalAnalyzer(settings);
        this.blockDetector = new SearchResultBlockDetector(settings);
    }

    public SearchResultExtractionResult extract(RenderedPageDocument document) {
        List<String> diagnostics = new ArrayList<String>(document.captureWarnings);
        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        if (resolution.lowConfidence) {
            if (showsExplicitNoResults(document)) {
                diagnostics.add("explicit no-results indication on a page without a result region");
                return new SearchResultExtractionResult(SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS,
                        document.snapshotId, resolution.confidence,
                        java.util.Collections.<SearchResultCandidate>emptyList(), diagnostics);
            }
            diagnostics.add("layout resolution LOW_CONFIDENCE (confidence="
                    + resolution.confidence + ") — the layout was not understood, which is not "
                    + "the same as an empty result page");
            return failed(document, resolution, diagnostics);
        }

        SearchResultBlockDetector.Detection detection =
                blockDetector.detect(document, resolution);
        diagnostics.addAll(detection.rejectionReasons);
        if (detection.blocks.isEmpty()) {
            if (showsExplicitNoResults(document)) {
                diagnostics.add("explicit no-results indication — genuinely zero organic hits");
                return new SearchResultExtractionResult(SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS,
                        document.snapshotId, resolution.confidence,
                        java.util.Collections.<SearchResultCandidate>emptyList(), diagnostics);
            }
            diagnostics.add("a result region was resolved but no valid result block emerged");
            return failed(document, resolution, diagnostics);
        }

        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        Set<String> seenTargets = new HashSet<String>();
        for (DetectedResultBlock block : detection.blocks) {
            if (!seenTargets.add(block.primaryLink.resolvedTargetUrl)) {
                diagnostics.add(block.blockContainerId + ": duplicate target "
                        + block.primaryLink.resolvedTargetUrl);
                continue;
            }
            List<SearchResultSiteLink> siteLinks = new ArrayList<SearchResultSiteLink>();
            for (RenderedLinkDescriptor siteLink : block.siteLinks) {
                siteLinks.add(new SearchResultSiteLink(siteLink.resolvedTargetUrl,
                        siteLink.visibleText));
            }
            candidates.add(new SearchResultCandidate(
                    "candidate-" + candidates.size(), document.snapshotId,
                    block.primaryLink.resolvedTargetUrl, block.primaryLink.rawHref, block.title,
                    block.snippet, block.displayedDomain, block.rank,
                    resolution.organicResultsContainerId, block.blockContainerId,
                    block.structuralConfidence, block.primaryLinkConfidence, siteLinks));
        }
        if (candidates.isEmpty()) {
            diagnostics.add("all detected blocks were duplicates — nothing extractable");
            return failed(document, resolution, diagnostics);
        }
        return new SearchResultExtractionResult(SearchPageAnalysisOutcome.ORGANIC_RESULTS,
                document.snapshotId, resolution.confidence, candidates, diagnostics);
    }

    /** Diagnostics access for the technical-details rendering. */
    public SearchPageLayoutResolution resolveLayout(RenderedPageDocument document) {
        return analyzer.analyze(document);
    }

    private SearchResultExtractionResult failed(RenderedPageDocument document,
                                                SearchPageLayoutResolution resolution,
                                                List<String> diagnostics) {
        return new SearchResultExtractionResult(SearchPageAnalysisOutcome.EXTRACTION_FAILED,
                document.snapshotId, resolution.confidence,
                java.util.Collections.<SearchResultCandidate>emptyList(), diagnostics);
    }

    /** The configured no-results texts against every captured container excerpt (lower-cased). */
    private boolean showsExplicitNoResults(RenderedPageDocument document) {
        for (RenderedContainerDescriptor container : document.containers) {
            String excerpt = container.textExcerpt.toLowerCase(Locale.ROOT);
            for (String marker : settings.analysis.noResultsTexts) {
                if (!marker.isEmpty() && excerpt.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }
}
