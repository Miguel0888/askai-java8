package com.aresstack.askai.research.backend;

/**
 * The structured terminal result of one autonomous research turn — the ONLY basis for the user-facing result
 * card. Reason/limitation/action are stable machine tokens (localized in the UI); unknown tokens must degrade
 * readably, never crash.
 */
public final class ResearchRunOutcomeInfo {

    private final String promptId;
    private final String stopReason;
    private final int pagesVisited;
    private final int acceptedSources;
    private final int distinctHosts;
    private final int minimumSources;
    private final int minimumDistinctHosts;
    private final boolean recoverable;
    private final String limitation;
    private final String recommendedAction;

    public ResearchRunOutcomeInfo(String promptId, String stopReason, int pagesVisited, int acceptedSources,
                                  int distinctHosts, int minimumSources, int minimumDistinctHosts,
                                  boolean recoverable, String limitation, String recommendedAction) {
        this.promptId = promptId == null ? "" : promptId;
        this.stopReason = stopReason == null ? "" : stopReason;
        this.pagesVisited = pagesVisited;
        this.acceptedSources = acceptedSources;
        this.distinctHosts = distinctHosts;
        this.minimumSources = minimumSources;
        this.minimumDistinctHosts = minimumDistinctHosts;
        this.recoverable = recoverable;
        this.limitation = limitation == null ? "" : limitation;
        this.recommendedAction = recommendedAction == null ? "" : recommendedAction;
    }

    public String getPromptId() { return promptId; }
    public String getStopReason() { return stopReason; }
    public int getPagesVisited() { return pagesVisited; }
    public int getAcceptedSources() { return acceptedSources; }
    public int getDistinctHosts() { return distinctHosts; }
    public int getMinimumSources() { return minimumSources; }
    public int getMinimumDistinctHosts() { return minimumDistinctHosts; }
    public boolean isRecoverable() { return recoverable; }
    public String getLimitation() { return limitation; }
    public String getRecommendedAction() { return recommendedAction; }

    /** True when both evidence minimums are met (the state may still carry a recorded limitation). */
    public boolean isEvidenceSufficient() {
        return acceptedSources >= minimumSources && distinctHosts >= minimumDistinctHosts;
    }
}
