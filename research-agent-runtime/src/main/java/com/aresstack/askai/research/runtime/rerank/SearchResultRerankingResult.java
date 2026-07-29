package com.aresstack.askai.research.runtime.rerank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of reranking one query's organic candidates: the FULL reranked order (all scored
 * candidates, most relevant first) and the SELECTED survivors the selection policy admitted for
 * navigation. The selected list is a prefix-by-relevance of the reranked order after applying Top-N
 * and any optional score cut-offs; it is what the loop is allowed to open, in this order.
 */
public final class SearchResultRerankingResult {

    public final List<RerankedSearchResultCandidate> reranked;
    public final List<RerankedSearchResultCandidate> selected;

    public SearchResultRerankingResult(List<RerankedSearchResultCandidate> reranked,
                                       List<RerankedSearchResultCandidate> selected) {
        this.reranked = Collections.unmodifiableList(
                new ArrayList<RerankedSearchResultCandidate>(reranked));
        this.selected = Collections.unmodifiableList(
                new ArrayList<RerankedSearchResultCandidate>(selected));
    }
}
