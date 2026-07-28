package com.aresstack.askai.browser.search;

/**
 * Mechanical (DOM-structural) SERP analysis contract for the A3 slice: candidate result containers
 * are found by repeated-sibling structure and scored; only containers above the structural confidence
 * become extraction candidates. Defined NOW so A3 is implemented against settings from its first line.
 */
public final class SearchPageAnalysisSettings {

    public final int maximumCandidateContainers;
    public final int minimumContainerTextCharacters;
    /** Minimum characters of NON-link text — pure link lists are navigation, not results. */
    public final int minimumNonLinkTextCharacters;
    /** A result list is a repeated structure: minimum similar siblings to count as one. */
    public final int minimumRepeatedSiblingCount;
    /** Containers below this structural confidence (0..1) are never extraction candidates. */
    public final double minimumResultStructuralConfidence;
    /** Containers whose link-text density exceeds this ratio (0..1) count as navigation. */
    public final double maximumNavigationLinkDensity;

    // Scoring weights/penalties (non-negative; applied by the A3 scorer).
    public final double internalLinkWeight;
    public final double externalLinkWeight;
    public final double sameHostPenalty;
    public final double sameRegistrableDomainPenalty;
    public final double subdomainPenalty;
    public final double unknownDomainPenalty;

    public SearchPageAnalysisSettings(int maximumCandidateContainers, int minimumContainerTextCharacters,
                                      int minimumNonLinkTextCharacters, int minimumRepeatedSiblingCount,
                                      double minimumResultStructuralConfidence,
                                      double maximumNavigationLinkDensity, double internalLinkWeight,
                                      double externalLinkWeight, double sameHostPenalty,
                                      double sameRegistrableDomainPenalty, double subdomainPenalty,
                                      double unknownDomainPenalty) {
        this.maximumCandidateContainers = maximumCandidateContainers;
        this.minimumContainerTextCharacters = minimumContainerTextCharacters;
        this.minimumNonLinkTextCharacters = minimumNonLinkTextCharacters;
        this.minimumRepeatedSiblingCount = minimumRepeatedSiblingCount;
        this.minimumResultStructuralConfidence = minimumResultStructuralConfidence;
        this.maximumNavigationLinkDensity = maximumNavigationLinkDensity;
        this.internalLinkWeight = internalLinkWeight;
        this.externalLinkWeight = externalLinkWeight;
        this.sameHostPenalty = sameHostPenalty;
        this.sameRegistrableDomainPenalty = sameRegistrableDomainPenalty;
        this.subdomainPenalty = subdomainPenalty;
        this.unknownDomainPenalty = unknownDomainPenalty;
    }
}
