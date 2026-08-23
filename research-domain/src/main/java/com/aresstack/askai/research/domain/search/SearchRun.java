package com.aresstack.askai.research.domain.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One search, as a RESULT rather than as a side effect: the query, how it was run, and the candidates it
 * produced.
 * <p>
 * The decisive property: <b>discovery succeeding does not depend on anything being read.</b> A run with
 * three result pages, 42 candidates and zero visited pages is complete and valid — that is what a scoping
 * orientation wants. Equally, a run whose selection or page inspection later fails keeps its candidates;
 * mandatory reranking is a precondition for AUTOMATIC page inspection, never for a candidate's existence.
 */
public final class SearchRun {

    /** How the discovery itself concluded — deliberately separate from what happened to the pages. */
    public enum Status {
        /** Candidates were produced. */
        RESULTS,
        /** The search ran and honestly found nothing. */
        NO_RESULTS,
        /** The search could not be carried out (SERP unreadable, provider error) — retryable. */
        TECHNICAL_PROBLEM
    }

    private final String runId;
    private final String query;
    private final String provider;
    private final int serpPagesCollected;
    private final Status status;
    private final List<SearchCandidate> candidates;

    public SearchRun(String runId, String query, String provider, int serpPagesCollected, Status status,
                     List<SearchCandidate> candidates) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("runId must not be empty");
        }
        this.runId = runId.trim();
        this.query = query == null ? "" : query.trim();
        this.provider = provider == null ? "" : provider.trim();
        this.serpPagesCollected = Math.max(0, serpPagesCollected);
        this.candidates = candidates == null || candidates.isEmpty()
                ? Collections.<SearchCandidate>emptyList()
                : Collections.unmodifiableList(new ArrayList<SearchCandidate>(candidates));
        // An explicit technical problem stays what it is; otherwise the candidates decide. This keeps the
        // honest distinction "found nothing" vs "could not search" that the runtime already makes.
        this.status = status == Status.TECHNICAL_PROBLEM ? status
                : (this.candidates.isEmpty() ? Status.NO_RESULTS : Status.RESULTS);
    }

    public String getRunId() {
        return runId;
    }

    public String getQuery() {
        return query;
    }

    public String getProvider() {
        return provider;
    }

    /** How many result pages were traversed — 3 pages with 0 visits is a normal, successful run. */
    public int getSerpPagesCollected() {
        return serpPagesCollected;
    }

    public Status getStatus() {
        return status;
    }

    public List<SearchCandidate> getCandidates() {
        return candidates;
    }

    public SearchCandidate candidate(String candidateId) {
        if (candidateId == null) {
            return null;
        }
        for (SearchCandidate candidate : candidates) {
            if (candidate.getCandidateId().equals(candidateId.trim())) {
                return candidate;
            }
        }
        return null;
    }

    /** How many candidates were actually read — zero is a valid outcome, not a failure. */
    public int inspectedCount() {
        int inspected = 0;
        for (SearchCandidate candidate : candidates) {
            if (candidate.getStatus() == SearchCandidate.Status.INSPECTED) {
                inspected++;
            }
        }
        return inspected;
    }

    /** The run with one candidate in a new state; everything else is untouched. */
    public SearchRun withCandidateStatus(String candidateId, SearchCandidate.Status newStatus) {
        List<SearchCandidate> updated = new ArrayList<SearchCandidate>(candidates.size());
        for (SearchCandidate candidate : candidates) {
            updated.add(candidate.getCandidateId().equals(candidateId)
                    ? candidate.withStatus(newStatus) : candidate);
        }
        return new SearchRun(runId, query, provider, serpPagesCollected, status, updated);
    }

    /** A short line for logs and the run outcome: what discovery produced, independent of any visit. */
    public String describe() {
        return "run=" + runId + " status=" + status + " serpPages=" + serpPagesCollected
                + " candidates=" + candidates.size() + " inspected=" + inspectedCount();
    }
}
