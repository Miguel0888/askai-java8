package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * One typed organic search candidate extracted from a recognized result block. The
 * {@link #resolvedTargetUrl} is what will be NAVIGATED; {@link #rawSearchHref} is diagnostic
 * provenance only (redirect wrappers are never opened). Container references are valid only
 * together with {@link #snapshotId} — against another snapshot they are stale.
 */
public final class SearchResultCandidate {

    public final String candidateId;
    public final String snapshotId;
    public final String resolvedTargetUrl;
    public final String rawSearchHref;
    public final String title;
    public final String snippet;
    public final String displayedDomain;
    /** 1-based rank in the engine's original order. */
    public final int originalRank;
    public final String resultContainerId;
    public final String resultBlockContainerId;
    public final double structuralConfidence;
    public final double primaryLinkConfidence;
    /** Stored as metadata; in A3 sitelinks are never emitted as own initial candidates. */
    public final List<SearchResultSiteLink> siteLinks;

    public SearchResultCandidate(String candidateId, String snapshotId, String resolvedTargetUrl,
                                 String rawSearchHref, String title, String snippet,
                                 String displayedDomain, int originalRank,
                                 String resultContainerId, String resultBlockContainerId,
                                 double structuralConfidence, double primaryLinkConfidence,
                                 List<SearchResultSiteLink> siteLinks) {
        this.candidateId = candidateId;
        this.snapshotId = snapshotId;
        this.resolvedTargetUrl = resolvedTargetUrl == null ? "" : resolvedTargetUrl;
        this.rawSearchHref = rawSearchHref == null ? "" : rawSearchHref;
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.displayedDomain = displayedDomain == null ? "" : displayedDomain;
        this.originalRank = originalRank;
        this.resultContainerId = resultContainerId == null ? "" : resultContainerId;
        this.resultBlockContainerId = resultBlockContainerId == null ? "" : resultBlockContainerId;
        this.structuralConfidence = structuralConfidence;
        this.primaryLinkConfidence = primaryLinkConfidence;
        this.siteLinks = Collections.unmodifiableList(siteLinks);
    }
}
