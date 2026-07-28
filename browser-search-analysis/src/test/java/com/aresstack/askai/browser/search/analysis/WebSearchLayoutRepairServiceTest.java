package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.DomStructureSignature;
import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The model-free sidecar bridge core: HIGH_CONFIDENCE extracts directly, LOW_CONFIDENCE emits a
 * bounded cached repair request, and applying a runtime-validated decision re-checks every guard
 * (unknown / expired / consumed / snapshot / fingerprint / invalid) before running the single A3
 * extraction. The service never calls a model.
 */
public class WebSearchLayoutRepairServiceTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
    private final LegacyBrowserSearchSettings lowConf =
            LayoutTestSupport.forcingLowConfidence(defaults);

    private RenderedPageDocument columnDocument(String[] outCol) {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        String col = serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        if (outCol != null) {
            outCol[0] = col;
        }
        return serp.build();
    }

    private WebSearchLayoutRepairService service(LegacyBrowserSearchSettings settings) {
        return new WebSearchLayoutRepairService(settings, 4, 10_000L);
    }

    private SearchLayoutRepairSubmission submission(String attemptId, String snapshotId,
                                                    String fingerprint, RenderedPageDocument doc,
                                                    String organicId) {
        ValidatedSearchPageLayoutDecision decision = new ValidatedSearchPageLayoutDecision(
                "analysis-x", doc.snapshotId, organicId, Arrays.asList(organicId),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9);
        return new SearchLayoutRepairSubmission(new SearchLayoutRepairAttemptId(attemptId),
                snapshotId, fingerprint, decision);
    }

    @Test
    public void highConfidencePreparesOrganicWithoutCaching() {
        WebSearchLayoutRepairService service = service(defaults);
        PreparedWebSearchResult result =
                service.prepareSingle(columnDocument(null), "q", "engine.example", 1000L);

        assertEquals(WebSearchPreparationStatus.ORGANIC_RESULTS, result.status);
        assertEquals(3, result.candidates.size());
        assertEquals("no repair attempt cached on a high-confidence page", 0, service.cache().size());
    }

    @Test
    public void lowConfidenceEmitsABoundedCachedRepairRequest() {
        WebSearchLayoutRepairService service = service(lowConf);
        RenderedPageDocument document = columnDocument(null);
        PreparedWebSearchResult result =
                service.prepareSingle(document, "berlin", "engine.example", 1000L);

        assertEquals(WebSearchPreparationStatus.REPAIR_REQUIRED, result.status);
        assertEquals(1, result.repairRequests.size());
        SearchLayoutRepairRequest request = result.repairRequests.get(0);
        assertEquals(document.snapshotId, request.snapshotId);
        assertEquals("f", request.documentFingerprint);
        assertEquals("berlin", request.query);
        assertTrue(request.expiresAtEpochMillis > request.createdAtEpochMillis);
        assertEquals(1, service.cache().size());
    }

    @Test
    public void applyingAValidatedDecisionYieldsRealCandidatesAndConsumesTheAttempt() {
        WebSearchLayoutRepairService service = service(lowConf);
        String[] col = new String[1];
        RenderedPageDocument document = columnDocument(col);
        PreparedWebSearchResult prepared =
                service.prepareSingle(document, "q", "engine.example", 1000L);
        String attemptId = prepared.repairRequests.get(0).attemptId.value;

        SearchLayoutRepairResult applied = service.apply(
                submission(attemptId, document.snapshotId, "f", document, col[0]), 2000L);

        assertEquals(SearchLayoutRepairStatus.ORGANIC_RESULTS, applied.status);
        assertEquals(3, applied.candidates.size());
        assertEquals("Result 0 title", applied.candidates.get(0).title);
        assertEquals("attempt consumed after application", 0, service.cache().size());

        SearchLayoutRepairResult again = service.apply(
                submission(attemptId, document.snapshotId, "f", document, col[0]), 2000L);
        assertEquals(SearchLayoutRepairStatus.UNKNOWN_ATTEMPT, again.status);
    }

    @Test
    public void guardMatrixIsEnforced() {
        String[] col = new String[1];
        RenderedPageDocument document = columnDocument(col);

        // unknown attempt
        assertEquals(SearchLayoutRepairStatus.UNKNOWN_ATTEMPT, service(lowConf)
                .apply(submission("nope", document.snapshotId, "f", document, col[0]), 2000L).status);

        // expired attempt
        WebSearchLayoutRepairService expiring = service(lowConf);
        String id = expiring.prepareSingle(document, "q", "e", 1000L).repairRequests.get(0)
                .attemptId.value;
        assertEquals(SearchLayoutRepairStatus.EXPIRED_ATTEMPT, expiring
                .apply(submission(id, document.snapshotId, "f", document, col[0]), 99_000L).status);

        // snapshot mismatch
        WebSearchLayoutRepairService s1 = service(lowConf);
        String id1 = s1.prepareSingle(document, "q", "e", 1000L).repairRequests.get(0).attemptId.value;
        assertEquals(SearchLayoutRepairStatus.SNAPSHOT_MISMATCH, s1
                .apply(submission(id1, "wrong-snap", "f", document, col[0]), 2000L).status);

        // fingerprint mismatch
        WebSearchLayoutRepairService s2 = service(lowConf);
        String id2 = s2.prepareSingle(document, "q", "e", 1000L).repairRequests.get(0).attemptId.value;
        assertEquals(SearchLayoutRepairStatus.FINGERPRINT_MISMATCH, s2
                .apply(submission(id2, document.snapshotId, "wrong-fp", document, col[0]), 2000L)
                .status);

        // invalid decision (unknown container id)
        WebSearchLayoutRepairService s3 = service(lowConf);
        String id3 = s3.prepareSingle(document, "q", "e", 1000L).repairRequests.get(0).attemptId.value;
        assertEquals(SearchLayoutRepairStatus.INVALID_DECISION, s3
                .apply(submission(id3, document.snapshotId, "f", document, "container-9999"), 2000L)
                .status);
    }

    @Test
    public void explicitNoResultsPreparesNoOrganicWithoutCaching() {
        WebSearchLayoutRepairService service = service(lowConf);
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        RenderedPageDocument document =
                withExcerpt(serp.build(), "Keine Ergebnisse für diese Suche gefunden.");
        PreparedWebSearchResult result = service.prepareSingle(document, "q", "e", 1000L);

        assertEquals(WebSearchPreparationStatus.NO_ORGANIC_RESULTS, result.status);
        assertEquals(0, service.cache().size());
    }

    @Test
    public void mergeOffersAllRepairRequestsInEngineOrderWhenNoOrganicHit() {
        WebSearchLayoutRepairService service = service(lowConf);
        SerpDocuments a = SerpDocuments.builder();
        a.addNavigationBar(8);
        a.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        PreparedWebSearchResult first = service.prepareSingle(a.build(), "q", "bing.example", 1000L);
        WebSearchLayoutRepairService service2 = service(lowConf);
        SerpDocuments b = SerpDocuments.builder();
        b.addPlainContainer("div", "x", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(0, 0, 100, 40), 40, 0, 0, 0, 0);
        b.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        PreparedWebSearchResult second = service2.prepareSingle(b.build(), "q", "ddg.example", 1000L);

        PreparedWebSearchResult merged =
                WebSearchLayoutRepairService.merge(Arrays.asList(first, second));
        assertEquals(WebSearchPreparationStatus.REPAIR_REQUIRED, merged.status);
        assertEquals(2, merged.repairRequests.size());
        assertEquals("bing.example", merged.repairRequests.get(0).engineHost);
        assertEquals("ddg.example", merged.repairRequests.get(1).engineHost);
    }

    private static RenderedPageDocument withExcerpt(RenderedPageDocument document, String excerpt) {
        List<RenderedContainerDescriptor> containers =
                new ArrayList<RenderedContainerDescriptor>(document.containers);
        containers.add(RenderedContainerDescriptor.builder("container-9999")
                .hierarchy("container-0001", Collections.<String>emptyList(), 9, 1)
                .semantics("div", "no-results", Collections.<String>emptyList(), "", "",
                        Collections.<String>emptyList())
                .text(excerpt, excerpt.length(), 0, excerpt.length(), 0, 1)
                .links(0, 0, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(300, 200, 680, 60), 1.0, false, 0.1, 0.2)
                .colors(SerpDocuments.WHITE, SerpDocuments.WHITE, 0, 0)
                .separation("", 0, "", 0, 0)
                .structure(new DomStructureSignature("div"), 0)
                .build());
        return new RenderedPageDocument(document.snapshotId, document.snapshotGeneration,
                document.pageUrl, document.pageTitle, document.viewport,
                document.documentFingerprint, document.rootContainerIds, containers,
                document.links, document.captureTruncated, document.captureWarnings);
    }
}
