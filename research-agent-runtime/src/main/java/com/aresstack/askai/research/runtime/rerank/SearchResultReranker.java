package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * A5c orchestrator: the MANDATORY reranking step between structured search extraction and browser
 * navigation. It turns each organic candidate into the text the cross-encoder scores — {@code Title:
 * …\nSnippet: …}, deliberately NOT the URL, so relevance is judged on content and not on domain
 * spelling — scores them all against the query, maps the RAW_LOGIT scores back onto the candidates and
 * hands them to the {@link SearchResultSelectionPolicy}. It never falls back to raw engine order: a
 * failure of the reranker is propagated as a typed {@link RerankerClientException}.
 */
public final class SearchResultReranker {

    private final HttpRerankerClient client;
    private final SearchResultSelectionPolicy policy;

    public SearchResultReranker(HttpRerankerClient client, SearchResultSelectionPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    /** The document text a candidate is scored by: its title and snippet, never its URL. */
    public static String documentText(SearchResultCandidate candidate) {
        return "Title: " + candidate.title + "\nSnippet: " + candidate.snippet;
    }

    /**
     * Rerank {@code candidates} for {@code query} and select the survivors. An empty input yields an
     * empty result without any network call.
     *
     * @throws RerankerClientException if the reranker cannot be reached or answers invalidly
     */
    public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates)
            throws RerankerClientException {
        if (candidates.isEmpty()) {
            List<RerankedSearchResultCandidate> empty =
                    new ArrayList<RerankedSearchResultCandidate>();
            return new SearchResultRerankingResult(empty, empty);
        }
        List<String> documents = new ArrayList<String>(candidates.size());
        for (SearchResultCandidate candidate : candidates) {
            documents.add(documentText(candidate));
        }

        List<RerankScore> scores = client.rerank(query, documents);

        // Map each scored row back onto its candidate; a candidate the endpoint did not score is
        // simply not ranked (and therefore never opened) — we never invent a score for it.
        List<RerankedSearchResultCandidate> scored =
                new ArrayList<RerankedSearchResultCandidate>(scores.size());
        for (RerankScore row : scores) {
            scored.add(new RerankedSearchResultCandidate(
                    candidates.get(row.documentIndex), row.score, 0));
        }
        return policy.select(scored);
    }
}
