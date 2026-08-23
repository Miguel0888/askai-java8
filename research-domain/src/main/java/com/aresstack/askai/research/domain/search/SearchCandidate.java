package com.aresstack.askai.research.domain.search;

/**
 * ONE hit a search produced, addressable by its {@link #getCandidateId()} for the rest of the project's
 * life — "open hit #18 again, properly" must not require running the search a second time.
 * <p>
 * Three things a candidate deliberately is NOT:
 * <ul>
 * <li>NOT a navigation target. What the acquisition engine works through also contains links discovered on
 *     visited pages and redirect targets; those have no rank, no snippet and a different provenance. That is
 *     a runtime-internal frontier entry, which may POINT AT a candidate.</li>
 * <li>NOT a source. A run may find a hundred candidates of which five end up in the research corpus. The
 *     relation is candidate → (inspection/acceptance) → source, not equality.</li>
 * <li>NOT proof that anything was read. A candidate exists as soon as the search engine returned it.</li>
 * </ul>
 */
public final class SearchCandidate {

    /** Where a candidate stands. It starts as DISCOVERED and never needs to progress at all. */
    public enum Status {
        /** Returned by the search — this alone is a complete, valid result. */
        DISCOVERED,
        /** Chosen for inspection by rank, diversity, the user or the agent. */
        SELECTED,
        /** Its page was successfully read. */
        INSPECTED,
        /** Deliberately not read (obstacle policy SKIP, budget spent, transit host, duplicate). */
        SKIPPED,
        /** Reading was attempted and failed. */
        FAILED
    }

    private final String candidateId;
    private final String url;
    private final String title;
    private final String snippet;
    private final String domain;
    private final int serpPage;
    private final int rank;
    private final String provider;
    private final Status status;

    public SearchCandidate(String candidateId, String url, String title, String snippet, String domain,
                           int serpPage, int rank, String provider, Status status) {
        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("candidateId must not be empty");
        }
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        this.candidateId = candidateId.trim();
        this.url = url.trim();
        this.title = title == null ? "" : title.trim();
        this.snippet = snippet == null ? "" : snippet.trim();
        this.domain = domain == null ? "" : domain.trim();
        this.serpPage = Math.max(1, serpPage);
        this.rank = Math.max(0, rank);
        this.provider = provider == null ? "" : provider.trim();
        this.status = status == null ? Status.DISCOVERED : status;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    /** What the result page promised — later also the expectation a readiness judge can check against. */
    public String getSnippet() {
        return snippet;
    }

    public String getDomain() {
        return domain;
    }

    /** Which result page it came from (1-based) — the reason a run can traverse several. */
    public int getSerpPage() {
        return serpPage;
    }

    /** Position within its result page, as the engine ordered it (never an evaluation). */
    public int getRank() {
        return rank;
    }

    /** The engine/provider that produced it. */
    public String getProvider() {
        return provider;
    }

    public Status getStatus() {
        return status;
    }

    /** The same candidate in a new state; identity and discovery data never change. */
    public SearchCandidate withStatus(Status newStatus) {
        return new SearchCandidate(candidateId, url, title, snippet, domain, serpPage, rank, provider,
                newStatus);
    }
}
