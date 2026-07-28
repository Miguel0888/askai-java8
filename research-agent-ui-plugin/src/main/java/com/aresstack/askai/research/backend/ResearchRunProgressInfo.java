package com.aresstack.askai.research.backend;

/**
 * One structured progress snapshot of a running autonomous research turn — the payload behind exactly ONE
 * in-place updated progress card per run. {@code activityToken} is a stable machine token; localization
 * happens in the UI, never here.
 */
public final class ResearchRunProgressInfo {

    private final String promptId;
    private final int pagesVisited;
    private final int acceptedSources;
    private final int distinctHosts;
    private final int toolCalls;
    private final String activityToken;
    private final String currentUrl;

    public ResearchRunProgressInfo(String promptId, int pagesVisited, int acceptedSources, int distinctHosts,
                                   int toolCalls, String activityToken, String currentUrl) {
        this.promptId = promptId == null ? "" : promptId;
        this.pagesVisited = pagesVisited;
        this.acceptedSources = acceptedSources;
        this.distinctHosts = distinctHosts;
        this.toolCalls = toolCalls;
        this.activityToken = activityToken == null ? "" : activityToken;
        this.currentUrl = currentUrl == null ? "" : currentUrl;
    }

    public String getPromptId() { return promptId; }
    public int getPagesVisited() { return pagesVisited; }
    public int getAcceptedSources() { return acceptedSources; }
    public int getDistinctHosts() { return distinctHosts; }
    public int getToolCalls() { return toolCalls; }
    public String getActivityToken() { return activityToken; }
    public String getCurrentUrl() { return currentUrl; }
}
