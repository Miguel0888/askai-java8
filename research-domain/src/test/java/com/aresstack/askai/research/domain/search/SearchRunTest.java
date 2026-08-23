package com.aresstack.askai.research.domain.search;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Discovery is a RESULT, not a step on the way to opening pages. The invariant this pins: a run that
 * collected result pages and read nothing is complete and successful — the case a scoping orientation
 * needs, and the case the old pipeline could not even express.
 */
public class SearchRunTest {

    private static SearchCandidate candidate(int index, String host) {
        return new SearchCandidate("c" + index, "https://" + host + "/page" + index, "Titel " + index,
                "Snippet " + index, host, 1 + (index / 10), index, "duckduckgo",
                SearchCandidate.Status.DISCOVERED);
    }

    private static SearchRun run(int candidateCount, int serpPages) {
        List<SearchCandidate> candidates = new ArrayList<SearchCandidate>();
        for (int index = 1; index <= candidateCount; index++) {
            candidates.add(candidate(index, "example" + (index % 7) + ".org"));
        }
        return new SearchRun("run-1", "truthahnragout tradition", "duckduckgo", serpPages,
                SearchRun.Status.RESULTS, candidates);
    }

    @Test
    public void aRunWithManyCandidatesAndZeroVisitsIsSuccessfulDiscovery() {
        SearchRun discovery = run(42, 3);

        assertEquals(SearchRun.Status.RESULTS, discovery.getStatus());
        assertEquals(42, discovery.getCandidates().size());
        assertEquals(3, discovery.getSerpPagesCollected());
        assertEquals("reading nothing is a valid outcome, not a failure", 0, discovery.inspectedCount());
        assertEquals("run=run-1 status=RESULTS serpPages=3 candidates=42 inspected=0",
                discovery.describe());
    }

    @Test
    public void candidatesSurviveWhateverHappensToTheirPages() {
        // Reranking/selection/inspection may fail later; the hits the engine returned remain hits.
        SearchRun discovery = run(5, 1)
                .withCandidateStatus("c2", SearchCandidate.Status.SKIPPED)
                .withCandidateStatus("c3", SearchCandidate.Status.FAILED)
                .withCandidateStatus("c4", SearchCandidate.Status.INSPECTED);

        assertEquals(5, discovery.getCandidates().size());
        assertEquals(SearchRun.Status.RESULTS, discovery.getStatus());
        assertEquals(SearchCandidate.Status.SKIPPED, discovery.candidate("c2").getStatus());
        assertEquals(SearchCandidate.Status.DISCOVERED, discovery.candidate("c1").getStatus());
        assertEquals(1, discovery.inspectedCount());
        assertEquals("a status change never touches the discovery data",
                "Titel 2", discovery.candidate("c2").getTitle());
    }

    @Test
    public void aCandidateStaysAddressableByItsIdRatherThanByItsUrl() {
        SearchRun discovery = run(20, 2);

        assertNotNull(discovery.candidate("c18"));
        assertEquals("https://example4.org/page18", discovery.candidate("c18").getUrl());
        assertEquals(2, discovery.candidate("c18").getSerpPage());
        assertNull(discovery.candidate("nope"));
        assertNull(discovery.candidate(null));
    }

    @Test
    public void anEmptySearchAndATechnicalFailureAreDifferentOutcomes() {
        SearchRun empty = new SearchRun("run-2", "q", "duckduckgo", 1, SearchRun.Status.RESULTS,
                Collections.<SearchCandidate>emptyList());
        assertEquals("no candidates means the search honestly found nothing",
                SearchRun.Status.NO_RESULTS, empty.getStatus());

        SearchRun broken = new SearchRun("run-3", "q", "duckduckgo", 0,
                SearchRun.Status.TECHNICAL_PROBLEM, Collections.<SearchCandidate>emptyList());
        assertEquals("a search that could not run must stay distinguishable from an empty one",
                SearchRun.Status.TECHNICAL_PROBLEM, broken.getStatus());
    }

    @Test
    public void discoveryDataOfACandidateIsImmutable() {
        SearchCandidate original = candidate(7, "example.org");
        SearchCandidate inspected = original.withStatus(SearchCandidate.Status.INSPECTED);

        assertEquals(SearchCandidate.Status.DISCOVERED, original.getStatus());
        assertEquals(SearchCandidate.Status.INSPECTED, inspected.getStatus());
        assertEquals(original.getCandidateId(), inspected.getCandidateId());
        assertEquals(original.getUrl(), inspected.getUrl());
        assertEquals(original.getRank(), inspected.getRank());
    }

    @Test
    public void theSameRunKeepsItsCandidateOrder() {
        SearchRun discovery = run(3, 1);
        assertEquals(Arrays.asList("c1", "c2", "c3"),
                Arrays.asList(discovery.getCandidates().get(0).getCandidateId(),
                        discovery.getCandidates().get(1).getCandidateId(),
                        discovery.getCandidates().get(2).getCandidateId()));
    }
}
