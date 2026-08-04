package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A4b: the resolver builds the prompt, drives the neutral inference port and parses the answer.
 * Disabled → never calls the model; no adapter → typed AI_UNAVAILABLE (no fake success); a valid
 * answer resolves; parse failures repair within the bounded retry budget; cancellation is honoured.
 */
public class AiSearchPageLayoutResolverTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    private SearchPageAnalysisArtifact artifact() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(9);
        serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        RenderedPageDocument document = serp.build();
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(defaults).analyze(document);
        return new SearchPageAnalysisArtifactBuilder(defaults).build(document, resolution, "q");
    }

    private SearchPageLayoutResolutionRequest request(SearchPageAnalysisArtifact artifact,
                                                      boolean enabled, int maxAttempts) {
        return new SearchPageLayoutResolutionRequest(artifact,
                LayoutTestSupport.aiSettings(enabled, "profile-x",
                        LayoutTestSupport.retryPolicy(maxAttempts)),
                defaults.diagnostics, CancellationSignal.NONE);
    }

    private String validResponse(SearchPageAnalysisArtifact artifact) {
        String id = artifact.containerCandidates.get(0).containerId;
        return "{\"analysisId\":\"" + artifact.analysisId + "\",\"snapshotId\":\"" + artifact.snapshotId + "\","
                + "\"organicResultContainerIds\":[\"" + id + "\"],"
                + "\"resultBlockContainerIds\":[],"
                + "\"excludedContainerIds\":[],"
                + "\"confidence\":0.9,\"explanation\":\"repeated blocks\"}";
    }

    @Test
    public void disabledResolverNeverCallsTheModel() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort();
        SearchPageLayoutResolverResult result =
                new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request(artifact, false, 3));

        assertEquals(SearchPageLayoutResolverOutcome.AI_DISABLED, result.outcome);
        assertEquals("model must never be called when disabled", 0, port.callCount());
    }

    @Test
    public void missingAdapterYieldsTypedUnavailableNotFakeSuccess() {
        SearchPageAnalysisArtifact artifact = artifact();
        SearchPageLayoutResolverResult result = new AiSearchPageLayoutResolver(
                new UnavailableStructuredInferencePort(), defaults.extraction)
                .resolve(request(artifact, true, 3));

        assertEquals(SearchPageLayoutResolverOutcome.AI_UNAVAILABLE, result.outcome);
        assertNull(result.acceptedDecision);
    }

    @Test
    public void validResponseResolvesAndEchoesSnapshot() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port =
                new ScriptedStructuredInferencePort().thenSuccess(validResponse(artifact));
        SearchPageLayoutResolverResult result =
                new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request(artifact, true, 3));

        assertEquals(SearchPageLayoutResolverOutcome.RESOLVED, result.outcome);
        assertNotNull(result.acceptedDecision);
        assertEquals(artifact.snapshotId, result.acceptedDecision.snapshotId);
        assertEquals(1, port.callCount());
    }

    @Test
    public void sponsoredContainersStayExcludedFromTheOrganicSelection() {
        // A DuckDuckGo-style variant: the model classifies one candidate as an advertisement. The
        // accepted decision carries it ONLY as excluded — the organic set never contains it. This is
        // the generic classification path, no engine- or ad-specific special case.
        SearchPageAnalysisArtifact artifact = artifact();
        assertTrue("fixture must offer at least two candidates",
                artifact.containerCandidates.size() >= 2);
        String organic = artifact.containerCandidates.get(0).containerId;
        String sponsored = artifact.containerCandidates.get(1).containerId;
        String response = "{\"analysisId\":\"" + artifact.analysisId + "\",\"snapshotId\":\""
                + artifact.snapshotId + "\","
                + "\"organicResultContainerIds\":[\"" + organic + "\"],"
                + "\"resultBlockContainerIds\":[],"
                + "\"excludedContainerIds\":[\"" + sponsored + "\"],"
                + "\"confidence\":0.9,\"explanation\":\"second candidate is a sponsored module\"}";
        ScriptedStructuredInferencePort port =
                new ScriptedStructuredInferencePort().thenSuccess(response);
        SearchPageLayoutResolverResult result =
                new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request(artifact, true, 3));

        assertEquals(SearchPageLayoutResolverOutcome.RESOLVED, result.outcome);
        assertNotNull(result.acceptedDecision);
        assertTrue(result.acceptedDecision.organicResultContainerIds.contains(organic));
        assertTrue("an advertisement container must never enter the organic selection",
                !result.acceptedDecision.organicResultContainerIds.contains(sponsored));
    }

    @Test
    public void parseFailureRepairsWithinBudgetThenResolves() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess("not json at all")
                .thenSuccess(validResponse(artifact));
        SearchPageLayoutResolverResult result =
                new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request(artifact, true, 3));

        assertEquals(SearchPageLayoutResolverOutcome.RESOLVED, result.outcome);
        assertEquals("must have repaired after one bad answer", 2, port.callCount());
        assertEquals(2, result.attempts.size());
        assertTrue("first attempt records the parse violation",
                !result.attempts.get(0).violations.isEmpty());
    }

    @Test
    public void exhaustedRetriesFailTypedWithFullAttemptHistory() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess("nope").thenSuccess("still nope");
        SearchPageLayoutResolverResult result =
                new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request(artifact, true, 2));

        assertEquals(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, result.outcome);
        assertEquals(2, result.attempts.size());
        assertNull(result.acceptedDecision);
    }

    @Test
    public void cancellationBeforeFirstAttemptIsTyped() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port =
                new ScriptedStructuredInferencePort().thenSuccess(validResponse(artifact));
        SearchPageLayoutResolutionRequest request = new SearchPageLayoutResolutionRequest(artifact,
                LayoutTestSupport.aiSettings(true, "profile-x", LayoutTestSupport.retryPolicy(3)),
                defaults.diagnostics, new CancellationSignal() {
            public boolean isCancelled() {
                return true;
            }
        });
        SearchPageLayoutResolverResult result = new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request);

        assertEquals(SearchPageLayoutResolverOutcome.CANCELLED, result.outcome);
        assertEquals(0, port.callCount());
    }
}
