package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The typed hand-off a {@link SearchStrategy} produces: the initial organic candidates (already the neutral
 * {@link SearchResultCandidate} the mandatory reranker consumes), the pure-transit provider hosts to skip
 * as sources, and any typed challenge states. After this the research loop's behaviour is IDENTICAL
 * regardless of whether the candidates came from a browser SERP or from an API provider — the reranker,
 * frontier, Playwright navigation, link discovery, capture and source acceptance are untouched.
 *
 * <p>{@code providerHosts} is meaningful only for the legacy browser path (the search engine's own site is
 * transit). API providers return direct target URLs and leave it empty. {@code challenges} likewise only
 * arises from the browser path.</p>
 */
public final class InitialSearchResult {

    /** Organic candidates in provider/engine order; reranking decides what actually reaches the frontier. */
    public final List<SearchResultCandidate> candidates;
    /** Search-engine transit hosts (never a source/page/link farm) — empty for API providers. */
    public final List<String> providerHosts;
    /** Typed CAPTCHA/consent challenge states — empty for API providers. */
    public final List<SearchChallengeState> challenges;
    /** Human-readable provenance/diagnostics for the run log. */
    public final List<String> diagnostics;

    public InitialSearchResult(List<SearchResultCandidate> candidates, List<String> providerHosts,
                               List<SearchChallengeState> challenges, List<String> diagnostics) {
        this.candidates = Collections.unmodifiableList(
                new ArrayList<SearchResultCandidate>(candidates));
        this.providerHosts = Collections.unmodifiableList(new ArrayList<String>(providerHosts));
        this.challenges = Collections.unmodifiableList(new ArrayList<SearchChallengeState>(challenges));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<String>(diagnostics));
    }

    /** An empty result (no candidates, no transit, no challenges) with the given diagnostics. */
    public static InitialSearchResult empty(List<String> diagnostics) {
        List<SearchResultCandidate> noCandidates = Collections.emptyList();
        List<String> noHosts = Collections.emptyList();
        List<SearchChallengeState> noChallenges = Collections.emptyList();
        return new InitialSearchResult(noCandidates, noHosts, noChallenges, diagnostics);
    }
}
