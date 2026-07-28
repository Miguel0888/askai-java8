package com.aresstack.askai.browser.search.layout;

import java.util.Collections;
import java.util.List;

/**
 * The typed result of the AI layout resolver: the {@link SearchPageLayoutResolverOutcome}, the
 * snapshot it is bound to, the accepted raw decision (only on {@link SearchPageLayoutResolverOutcome#RESOLVED}),
 * the full ordered attempt history (including every failed attempt) and a short diagnostic. A4c adds
 * the validated decision the extractor consumes; here the accepted decision is the parsed model
 * answer.
 */
public final class SearchPageLayoutResolverResult {

    public final SearchPageLayoutResolverOutcome outcome;
    public final String snapshotId;
    public final SearchPageLayoutResolutionDecision acceptedDecision;
    public final List<SearchPageAnalysisAttempt> attempts;
    public final String diagnostic;

    public SearchPageLayoutResolverResult(SearchPageLayoutResolverOutcome outcome, String snapshotId,
                                          SearchPageLayoutResolutionDecision acceptedDecision,
                                          List<SearchPageAnalysisAttempt> attempts, String diagnostic) {
        this.outcome = outcome == null ? SearchPageLayoutResolverOutcome.AI_UNAVAILABLE : outcome;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.acceptedDecision = acceptedDecision;
        this.attempts = attempts == null
                ? Collections.<SearchPageAnalysisAttempt>emptyList()
                : Collections.unmodifiableList(attempts);
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public boolean isResolved() {
        return outcome == SearchPageLayoutResolverOutcome.RESOLVED && acceptedDecision != null;
    }
}
