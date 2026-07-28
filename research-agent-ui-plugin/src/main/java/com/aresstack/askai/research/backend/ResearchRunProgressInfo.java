package com.aresstack.askai.research.backend;

/**
 * One structured progress snapshot of a running autonomous research turn — the payload behind exactly ONE
 * in-place updated progress card per run. {@code activityToken} is a stable machine token; localization
 * happens in the UI, never here. The activity context (search query, final post-redirect URL/host, page
 * title) is what makes the live browsing understandable to the user.
 */
public final class ResearchRunProgressInfo {

    private final String promptId;
    private final int pagesVisited;
    private final int acceptedSources;
    private final int distinctHosts;
    private final int toolCalls;
    private final String activityToken;
    private final String searchQuery;
    private final String currentUrl;
    private final String currentHost;
    private final String currentPageTitle;

    public ResearchRunProgressInfo(String promptId, int pagesVisited, int acceptedSources, int distinctHosts,
                                   int toolCalls, String activityToken, String searchQuery,
                                   String currentUrl, String currentHost, String currentPageTitle) {
        this.promptId = promptId == null ? "" : promptId;
        this.pagesVisited = pagesVisited;
        this.acceptedSources = acceptedSources;
        this.distinctHosts = distinctHosts;
        this.toolCalls = toolCalls;
        this.activityToken = activityToken == null ? "" : activityToken;
        this.searchQuery = searchQuery == null ? "" : searchQuery;
        this.currentUrl = currentUrl == null ? "" : currentUrl;
        this.currentHost = currentHost == null ? "" : currentHost;
        this.currentPageTitle = currentPageTitle == null ? "" : currentPageTitle;
    }

    /** Convenience for callers without an activity context (counters-only snapshots). */
    public ResearchRunProgressInfo(String promptId, int pagesVisited, int acceptedSources, int distinctHosts,
                                   int toolCalls, String activityToken, String currentUrl) {
        this(promptId, pagesVisited, acceptedSources, distinctHosts, toolCalls, activityToken,
                "", currentUrl, "", "");
    }

    public String getPromptId() { return promptId; }
    public int getPagesVisited() { return pagesVisited; }
    public int getAcceptedSources() { return acceptedSources; }
    public int getDistinctHosts() { return distinctHosts; }
    public int getToolCalls() { return toolCalls; }
    public String getActivityToken() { return activityToken; }
    public String getSearchQuery() { return searchQuery; }
    public String getCurrentUrl() { return currentUrl; }
    public String getCurrentHost() { return currentHost; }
    public String getCurrentPageTitle() { return currentPageTitle; }
}
