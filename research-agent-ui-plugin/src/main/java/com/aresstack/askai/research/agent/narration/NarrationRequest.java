package com.aresstack.askai.research.agent.narration;

/**
 * One narration order: which chat activity (thought bubble) it belongs to, the static bubble title shown
 * instantly, the deterministic fallback text that is ALWAYS available — the narrator may only rephrase
 * it, and every failure path (timeout, error, validation) falls back to it with an identical visible
 * lifecycle — and optionally the {@link NarrationPayload} the result is validated against.
 */
public final class NarrationRequest {

    private final String activityId;
    private final String thinkingTitle;
    private final String fallbackText;
    private final NarrationPayload payload;   // null → no validation, fallback-only contract
    private final String retryHint;           // null → first attempt; else the violation to fix

    public NarrationRequest(String activityId, String thinkingTitle, String fallbackText) {
        this(activityId, thinkingTitle, fallbackText, null, null);
    }

    public NarrationRequest(String activityId, String thinkingTitle, String fallbackText,
                            NarrationPayload payload) {
        this(activityId, thinkingTitle, fallbackText, payload, null);
    }

    private NarrationRequest(String activityId, String thinkingTitle, String fallbackText,
                             NarrationPayload payload, String retryHint) {
        this.activityId = activityId == null ? "" : activityId;
        this.thinkingTitle = thinkingTitle == null ? "" : thinkingTitle;
        this.fallbackText = fallbackText == null ? "" : fallbackText;
        this.payload = payload;
        this.retryHint = retryHint;
    }

    /** The same order, re-issued with the validation violation the narrator must fix. */
    public NarrationRequest withRetryHint(String hint) {
        return new NarrationRequest(activityId, thinkingTitle, fallbackText, payload, hint);
    }

    public String getActivityId() {
        return activityId;
    }

    public String getThinkingTitle() {
        return thinkingTitle;
    }

    public String getFallbackText() {
        return fallbackText;
    }

    public NarrationPayload getPayload() {
        return payload;
    }

    public String getRetryHint() {
        return retryHint;
    }
}
