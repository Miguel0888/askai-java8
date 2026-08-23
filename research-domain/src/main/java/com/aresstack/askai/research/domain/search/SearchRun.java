package com.aresstack.askai.research.domain.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One search, as a RESULT rather than as a side effect: the query, the batches that were collected, the
 * candidates they produced, and — separately — what was later attempted on those candidates' pages.
 * <p>
 * The decisive property: <b>discovery succeeding does not depend on anything being read.</b> A run with
 * three batches, 42 candidates and zero inspections is complete and valid — what a scoping orientation
 * wants. Equally, mandatory reranking is a precondition for automatic page INSPECTION, never for a
 * candidate's existence: if selection or inspection fails later, the candidates remain untouched.
 * <p>
 * Inspections are a LIST, not a status: the same candidate may be skipped today and read tomorrow under a
 * different profile, and both attempts stay on record.
 */
public final class SearchRun {

    /** How the discovery itself concluded — deliberately separate from what happened to any page. */
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
    private final String profileName;
    private final Status status;
    private final List<DiscoveryBatch> batches;
    private final List<SearchCandidate> candidates;
    private final List<InspectionAttempt> inspections;

    public SearchRun(String runId, String query, String profileName, Status status,
                     List<DiscoveryBatch> batches, List<SearchCandidate> candidates,
                     List<InspectionAttempt> inspections) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("runId must not be empty");
        }
        this.runId = runId.trim();
        this.query = query == null ? "" : query.trim();
        this.profileName = profileName == null ? "" : profileName.trim();
        this.batches = copy(batches);
        this.candidates = copy(candidates);
        this.inspections = copy(inspections);
        // An explicit technical problem stays what it is; otherwise the candidates decide. This keeps the
        // honest distinction "found nothing" vs "could not search".
        this.status = status == Status.TECHNICAL_PROBLEM ? status
                : (this.candidates.isEmpty() ? Status.NO_RESULTS : Status.RESULTS);
    }

    /** A discovery-only run: batches and candidates, nothing inspected. */
    public static SearchRun discovered(String runId, String query, String profileName, Status status,
                                       List<DiscoveryBatch> batches, List<SearchCandidate> candidates) {
        return new SearchRun(runId, query, profileName, status, batches, candidates,
                Collections.<InspectionAttempt>emptyList());
    }

    public String getRunId() {
        return runId;
    }

    public String getQuery() {
        return query;
    }

    /** The strategy profile this run was executed under — the WHY behind its numbers. */
    public String getProfileName() {
        return profileName;
    }

    public Status getStatus() {
        return status;
    }

    /** The result portions that were collected; their count is the traversal depth of this run. */
    public List<DiscoveryBatch> getBatches() {
        return batches;
    }

    public List<SearchCandidate> getCandidates() {
        return candidates;
    }

    /** Every inspection attempt made on this run's candidates, in the order they happened. */
    public List<InspectionAttempt> getInspections() {
        return inspections;
    }

    /** The providers that contributed to this run, in first-seen order. */
    public List<String> providers() {
        List<String> providers = new ArrayList<String>();
        for (DiscoveryBatch batch : batches) {
            if (!batch.getProvider().isEmpty() && !providers.contains(batch.getProvider())) {
                providers.add(batch.getProvider());
            }
        }
        return Collections.unmodifiableList(providers);
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

    /**
     * The most recent attempt on this candidate, or {@code null} when it was never looked at. This is the
     * PROJECTION a UI shows — the attempts themselves stay the truth.
     */
    public InspectionAttempt latestInspection(String candidateId) {
        InspectionAttempt latest = null;
        for (InspectionAttempt attempt : inspections) {
            if (attempt.getCandidateId().equals(candidateId)
                    && (latest == null || attempt.getAttemptedAt() >= latest.getAttemptedAt())) {
                latest = attempt;
            }
        }
        return latest;
    }

    /** How many DISTINCT candidates were successfully read; zero is a valid outcome, not a failure. */
    public int readCount() {
        List<String> read = new ArrayList<String>();
        for (InspectionAttempt attempt : inspections) {
            if (attempt.isRead() && !read.contains(attempt.getCandidateId())) {
                read.add(attempt.getCandidateId());
            }
        }
        return read.size();
    }

    /** The run with one more inspection attempt recorded; discovery data is never rewritten. */
    public SearchRun withInspection(InspectionAttempt attempt) {
        if (attempt == null) {
            return this;
        }
        List<InspectionAttempt> extended = new ArrayList<InspectionAttempt>(inspections);
        extended.add(attempt);
        return new SearchRun(runId, query, profileName, status, batches, candidates, extended);
    }

    /** A short line for logs and the run outcome: what discovery produced, independent of any visit. */
    public String describe() {
        return "run=" + runId + " status=" + status + " batches=" + batches.size()
                + " candidates=" + candidates.size() + " read=" + readCount();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
