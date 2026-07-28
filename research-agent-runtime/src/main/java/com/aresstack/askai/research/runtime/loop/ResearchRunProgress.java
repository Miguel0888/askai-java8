package com.aresstack.askai.research.runtime.loop;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mutable state of one run. Page counting is precise: {@link #pageVisited} increments ONLY for a successful
 * navigation to a NEW canonical URL (web_open/web_follow); web_read/web_links/web_back and re-opens never
 * count. {@code consecutiveErrors} means truly consecutive — any success resets it to 0 (a separate
 * {@code totalErrors} keeps the overall count).
 */
public final class ResearchRunProgress {

    private int toolCalls;
    private int pagesVisited;
    private int acceptedSources;
    private int consecutiveErrors;
    private int totalErrors;
    private final Set<String> visitedCanonicalUrls = new LinkedHashSet<String>();
    private final Set<String> distinctHosts = new LinkedHashSet<String>();

    public void toolCall() {
        toolCalls++;
    }

    /** @return true when this canonical URL is NEW (and counts as a visited page). */
    public boolean pageVisited(String canonicalUrl, String host) {
        if (canonicalUrl == null || canonicalUrl.isEmpty() || !visitedCanonicalUrls.add(canonicalUrl)) {
            return false;
        }
        pagesVisited++;
        if (host != null && !host.isEmpty()) {
            distinctHosts.add(host);
        }
        return true;
    }

    public boolean alreadyVisited(String canonicalUrl) {
        return visitedCanonicalUrls.contains(canonicalUrl);
    }

    /**
     * Mark a canonical URL as visited WITHOUT counting a page or host — used for the requested (pre-redirect)
     * address of a page whose FINAL URL was counted, and to seed exclusions when a run continues.
     */
    public void noteVisitedAlias(String canonicalUrl) {
        if (canonicalUrl != null && !canonicalUrl.isEmpty()) {
            visitedCanonicalUrls.add(canonicalUrl);
        }
    }

    public void sourceAccepted() {
        acceptedSources++;
    }

    public void error() {
        consecutiveErrors++;
        totalErrors++;
    }

    public void success() {
        consecutiveErrors = 0;
    }

    public int getToolCalls() { return toolCalls; }
    public int getPagesVisited() { return pagesVisited; }
    public int getAcceptedSources() { return acceptedSources; }
    public int getConsecutiveErrors() { return consecutiveErrors; }
    public int getTotalErrors() { return totalErrors; }
    public Set<String> getDistinctHosts() { return distinctHosts; }
    public Set<String> getVisitedCanonicalUrls() { return visitedCanonicalUrls; }
}
