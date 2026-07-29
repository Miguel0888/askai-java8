package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.util.List;

/**
 * The mandatory reranking seam between structured extraction and browser navigation. The productive
 * implementation is the HTTP-backed {@link SearchResultReranker}; tests that deliberately do not exercise
 * a real reranker inject the explicitly named {@link EngineOrderReranker} rather than relying on a null
 * reranker — production code never infers "engine order" from the ABSENCE of a reranker.
 */
public interface CandidateReranker {

    /**
     * Rerank the organic candidates for the query and select the survivors. Never throws for a reranker
     * failure — every outcome (unavailable, invalid, timeout, cancelled, no matches) is reported through
     * the returned {@link SearchResultRerankingResult#outcome}.
     */
    SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                       CancellationSignal cancellation);
}
