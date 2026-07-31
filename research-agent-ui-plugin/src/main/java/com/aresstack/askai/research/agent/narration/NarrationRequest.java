package com.aresstack.askai.research.agent.narration;

/**
 * One narration order: which chat activity (thought bubble) it belongs to, the static bubble title shown
 * instantly, and the deterministic fallback text that is ALWAYS available — the narrator may only rephrase
 * it, and every failure path (timeout, error, validation) falls back to it with an identical visible
 * lifecycle.
 */
public final class NarrationRequest {

    private final String activityId;
    private final String thinkingTitle;
    private final String fallbackText;

    public NarrationRequest(String activityId, String thinkingTitle, String fallbackText) {
        this.activityId = activityId == null ? "" : activityId;
        this.thinkingTitle = thinkingTitle == null ? "" : thinkingTitle;
        this.fallbackText = fallbackText == null ? "" : fallbackText;
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
}
