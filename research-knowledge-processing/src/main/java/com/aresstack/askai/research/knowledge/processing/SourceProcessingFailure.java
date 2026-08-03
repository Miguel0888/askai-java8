package com.aresstack.askai.research.knowledge.processing;

/**
 * A named failure of one processing stage (§24). {@code retryable} distinguishes a transient problem
 * (FAILED_RETRYABLE, may run again) from a permanent one (FAILED_PERMANENT). Carries no page content or
 * secrets (§29) — only the stage, a short reason and the retryable flag.
 */
public final class SourceProcessingFailure {

    private final SourceProcessingStage stage;
    private final String reason;
    private final boolean retryable;

    public SourceProcessingFailure(SourceProcessingStage stage, String reason, boolean retryable) {
        this.stage = stage;
        this.reason = reason == null ? "" : reason;
        this.retryable = retryable;
    }

    public static SourceProcessingFailure retryable(SourceProcessingStage stage, String reason) {
        return new SourceProcessingFailure(stage, reason, true);
    }

    public static SourceProcessingFailure permanent(SourceProcessingStage stage, String reason) {
        return new SourceProcessingFailure(stage, reason, false);
    }

    public SourceProcessingStage getStage() {
        return stage;
    }

    public String getReason() {
        return reason;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
