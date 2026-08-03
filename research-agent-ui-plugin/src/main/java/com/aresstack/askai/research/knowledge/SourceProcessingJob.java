package com.aresstack.askai.research.knowledge;

/**
 * A queued unit of capture processing (§4.2). A job moves QUEUED → PROCESSING → COMPLETED, or to
 * FAILED_RETRYABLE / FAILED_PERMANENT. A job left in PROCESSING by a crash is returned to QUEUED on recovery
 * (§4.2, §25). Immutable value; a state transition produces a new instance.
 */
public final class SourceProcessingJob {

    public enum State {
        QUEUED, PROCESSING, COMPLETED, FAILED_RETRYABLE, FAILED_PERMANENT
    }

    private final String jobId;
    private final SourceProcessingRequest request;
    private final State state;
    private final int attempts;
    private final long enqueuedAtEpochMillis;
    private final SourceProcessingFailure lastFailure;

    public SourceProcessingJob(String jobId, SourceProcessingRequest request, State state, int attempts,
                               long enqueuedAtEpochMillis, SourceProcessingFailure lastFailure) {
        if (jobId == null || jobId.trim().isEmpty()) {
            throw new IllegalArgumentException("jobId must not be empty");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        this.jobId = jobId;
        this.request = request;
        this.state = state == null ? State.QUEUED : state;
        this.attempts = attempts;
        this.enqueuedAtEpochMillis = enqueuedAtEpochMillis;
        this.lastFailure = lastFailure;
    }

    /** A fresh QUEUED job. */
    public static SourceProcessingJob queued(String jobId, SourceProcessingRequest request,
                                             long enqueuedAtEpochMillis) {
        return new SourceProcessingJob(jobId, request, State.QUEUED, 0, enqueuedAtEpochMillis, null);
    }

    public SourceProcessingJob withState(State next) {
        return new SourceProcessingJob(jobId, request, next, attempts, enqueuedAtEpochMillis, lastFailure);
    }

    public SourceProcessingJob startedProcessing() {
        return new SourceProcessingJob(jobId, request, State.PROCESSING, attempts + 1,
                enqueuedAtEpochMillis, lastFailure);
    }

    public SourceProcessingJob failed(SourceProcessingFailure failure) {
        return new SourceProcessingJob(jobId, request,
                failure != null && failure.isRetryable() ? State.FAILED_RETRYABLE : State.FAILED_PERMANENT,
                attempts, enqueuedAtEpochMillis, failure);
    }

    public String getJobId() {
        return jobId;
    }

    public SourceProcessingRequest getRequest() {
        return request;
    }

    public State getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getEnqueuedAtEpochMillis() {
        return enqueuedAtEpochMillis;
    }

    public SourceProcessingFailure getLastFailure() {
        return lastFailure;
    }
}
