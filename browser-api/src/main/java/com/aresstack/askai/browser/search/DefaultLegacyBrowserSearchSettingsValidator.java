package com.aresstack.askai.browser.search;

import java.util.ArrayList;
import java.util.List;

/**
 * The productive validator. Every rule reports the concrete flat setting key (codec key) so the
 * settings UI can attach the message to the offending field. Nothing is auto-corrected.
 */
public final class DefaultLegacyBrowserSearchSettingsValidator
        implements LegacyBrowserSearchSettingsValidator {

    @Override
    public SettingsValidationResult validate(LegacyBrowserSearchSettings s) {
        List<SettingsValidationResult.Violation> v =
                new ArrayList<SettingsValidationResult.Violation>();

        // --- navigation
        atLeast(v, "navigation.maximumEngineAttempts", s.navigation.maximumEngineAttempts, 1);
        positive(v, "navigation.navigationCommitTimeoutMillis",
                s.navigation.navigationCommitTimeoutMillis);
        atLeast(v, "navigation.maximumRedirectUrlLength", s.navigation.maximumRedirectUrlLength, 256);
        atLeast(v, "navigation.searchResultLimit", s.navigation.searchResultLimit, 1);
        for (String template : s.navigation.fallbackEngineTemplates) {
            if (!template.contains("{query}")) {
                v.add(new SettingsValidationResult.Violation("navigation.fallbackEngineTemplates",
                        "engine template must contain {query}: " + template));
            }
        }
        require(v, "navigation.engineSwitchPolicy", s.navigation.engineSwitchPolicy != null,
                "engine switch policy must be set");

        // --- consent
        atLeast(v, "consent.maximumDismissAttempts", s.consent.maximumDismissAttempts, 1);
        positive(v, "consent.detectionPollIntervalMillis", s.consent.detectionPollIntervalMillis);
        atLeast(v, "consent.detectionWindowMillis", s.consent.detectionWindowMillis, 0);
        atLeast(v, "consent.postClickSettleMillis", s.consent.postClickSettleMillis, 0);
        require(v, "consent.positiveButtonSelectors",
                !s.consent.enabled || !s.consent.positiveButtonSelectors.isEmpty()
                        || !s.consent.positiveButtonTexts.isEmpty(),
                "enabled consent handling needs at least one selector or positive text");

        // --- captcha
        positive(v, "captcha.challengeProbeIntervalMillis", s.captcha.challengeProbeIntervalMillis);
        require(v, "captcha.challengeSelectors",
                !s.captcha.enabled || !s.captcha.challengeSelectors.isEmpty()
                        || !s.captcha.challengeTexts.isEmpty(),
                "enabled challenge detection needs at least one selector or text");

        // --- readiness (four distinct clocks; each must be positive and consistent)
        positive(v, "readiness.pollIntervalMillis", s.readiness.pollIntervalMillis);
        atLeast(v, "readiness.settlePollCount", s.readiness.settlePollCount, 1);
        atLeast(v, "readiness.minimumReadableCharacters", s.readiness.minimumReadableCharacters, 0);
        positive(v, "readiness.contentReadinessTimeoutMillis",
                s.readiness.contentReadinessTimeoutMillis);
        positive(v, "readiness.navigationCommitTimeoutMillis",
                s.readiness.navigationCommitTimeoutMillis);
        positive(v, "readiness.maximumAwaitCallMillis", s.readiness.maximumAwaitCallMillis);
        require(v, "readiness.contentReadinessTimeoutMillis",
                s.readiness.contentReadinessTimeoutMillis
                        >= s.readiness.pollIntervalMillis * s.readiness.settlePollCount,
                "content readiness timeout must cover at least one full settle window");

        // --- analysis
        atLeast(v, "analysis.maximumCandidateContainers", s.analysis.maximumCandidateContainers, 1);
        atLeast(v, "analysis.minimumContainerTextCharacters",
                s.analysis.minimumContainerTextCharacters, 0);
        atLeast(v, "analysis.minimumNonLinkTextCharacters",
                s.analysis.minimumNonLinkTextCharacters, 0);
        atLeast(v, "analysis.minimumRepeatedSiblingCount", s.analysis.minimumRepeatedSiblingCount, 1);
        ratio(v, "analysis.minimumResultStructuralConfidence",
                s.analysis.minimumResultStructuralConfidence);
        ratio(v, "analysis.maximumNavigationLinkDensity", s.analysis.maximumNavigationLinkDensity);
        weight(v, "analysis.internalLinkWeight", s.analysis.internalLinkWeight);
        weight(v, "analysis.externalLinkWeight", s.analysis.externalLinkWeight);
        weight(v, "analysis.sameHostPenalty", s.analysis.sameHostPenalty);
        weight(v, "analysis.sameRegistrableDomainPenalty", s.analysis.sameRegistrableDomainPenalty);
        weight(v, "analysis.subdomainPenalty", s.analysis.subdomainPenalty);
        weight(v, "analysis.unknownDomainPenalty", s.analysis.unknownDomainPenalty);

        // --- visual analysis
        ratio(v, "visual.backgroundSimilarityThreshold",
                s.visualAnalysis.backgroundSimilarityThreshold);
        ratio(v, "visual.minimumDistinctBackgroundDistance",
                s.visualAnalysis.minimumDistinctBackgroundDistance);
        ratio(v, "visual.maximumDominantColorCoverage",
                s.visualAnalysis.maximumDominantColorCoverage);
        ratio(v, "visual.minimumVisualRegionAreaRatio",
                s.visualAnalysis.minimumVisualRegionAreaRatio);
        ratio(v, "visual.centerProbeXRatio", s.visualAnalysis.centerProbeXRatio);
        ratio(v, "visual.centerProbeYRatio", s.visualAnalysis.centerProbeYRatio);
        ratio(v, "visual.centerProbeWidthRatio", s.visualAnalysis.centerProbeWidthRatio);
        ratio(v, "visual.centerProbeHeightRatio", s.visualAnalysis.centerProbeHeightRatio);
        weight(v, "visual.centerIntersectionWeight", s.visualAnalysis.centerIntersectionWeight);
        weight(v, "visual.centerDistanceWeight", s.visualAnalysis.centerDistanceWeight);
        weight(v, "visual.distinctBackgroundWeight", s.visualAnalysis.distinctBackgroundWeight);
        weight(v, "visual.borderSeparationWeight", s.visualAnalysis.borderSeparationWeight);
        weight(v, "visual.shadowSeparationWeight", s.visualAnalysis.shadowSeparationWeight);
        weight(v, "visual.spacingSeparationWeight", s.visualAnalysis.spacingSeparationWeight);
        weight(v, "visual.regionContinuityWeight", s.visualAnalysis.regionContinuityWeight);
        weight(v, "visual.fullPageContainerPenalty", s.visualAnalysis.fullPageContainerPenalty);
        weight(v, "visual.edgeRegionPenalty", s.visualAnalysis.edgeRegionPenalty);
        atLeast(v, "visual.maximumVisualContainers", s.visualAnalysis.maximumVisualContainers, 1);

        // --- extraction
        atLeast(v, "extraction.minimumTitleCharacters", s.extraction.minimumTitleCharacters, 1);
        atLeast(v, "extraction.minimumSnippetCharacters", s.extraction.minimumSnippetCharacters, 0);
        require(v, "extraction.maximumSnippetCharacters",
                s.extraction.minimumSnippetCharacters <= s.extraction.maximumSnippetCharacters,
                "minimumSnippetCharacters must be <= maximumSnippetCharacters");
        atLeast(v, "extraction.maximumExtractedCandidates",
                s.extraction.maximumExtractedCandidates, 1);
        atLeast(v, "extraction.maximumSiteLinksPerResult", s.extraction.maximumSiteLinksPerResult, 0);
        ratio(v, "extraction.minimumPrimaryLinkConfidence",
                s.extraction.minimumPrimaryLinkConfidence);
        ratio(v, "extraction.minimumStructuralConfidenceForReranking",
                s.extraction.minimumStructuralConfidenceForReranking);

        // --- AI layout resolver
        require(v, "aiLayoutResolver.modelProfileId",
                !s.aiLayoutResolver.enabled || !s.aiLayoutResolver.modelProfileId.trim().isEmpty(),
                "the enabled AI layout resolver needs a model profile");
        require(v, "aiLayoutResolver.temperature",
                s.aiLayoutResolver.temperature >= 0 && s.aiLayoutResolver.temperature <= 2,
                "temperature must be between 0 and 2");
        atLeast(v, "aiLayoutResolver.maximumOutputTokens", s.aiLayoutResolver.maximumOutputTokens, 64);
        promptContains(v, "aiLayoutResolver.userPromptTemplate",
                s.aiLayoutResolver.userPromptTemplate, "{containerDescriptors}");
        promptContains(v, "aiLayoutResolver.userPromptTemplate",
                s.aiLayoutResolver.userPromptTemplate, "{query}");
        retryPolicy(v, "aiLayoutResolver.retry.", s.aiLayoutResolver.retryPolicy);

        // --- reranker
        require(v, "reranker.modelProfileId",
                !s.reranker.enabled
                        || s.reranker.implementationType == RerankerImplementationType.HEURISTIC
                        || !s.reranker.modelProfileId.trim().isEmpty(),
                "the enabled model-backed reranker needs a model profile");
        atLeast(v, "reranker.maximumCandidates", s.reranker.maximumCandidates, 1);
        atLeast(v, "reranker.maximumSelectedResults", s.reranker.maximumSelectedResults, 1);
        require(v, "reranker.maximumCandidates",
                s.reranker.maximumCandidates >= s.reranker.maximumSelectedResults,
                "candidate limit must be >= selected-result limit");
        weight(v, "reranker.structuralScoreWeight", s.reranker.structuralScoreWeight);
        weight(v, "reranker.semanticScoreWeight", s.reranker.semanticScoreWeight);
        weight(v, "reranker.originalRankWeight", s.reranker.originalRankWeight);
        promptContains(v, "reranker.promptTemplate", s.reranker.promptTemplate, "{candidates}");
        promptContains(v, "reranker.promptTemplate", s.reranker.promptTemplate, "{query}");
        retryPolicy(v, "reranker.retry.", s.reranker.retryPolicy);

        // --- diagnostics
        atLeast(v, "diagnostics.maximumTextExcerptCharacters",
                s.diagnostics.maximumTextExcerptCharacters, 0);
        atLeast(v, "diagnostics.maximumDiagnosticArtifactBytes",
                s.diagnostics.maximumDiagnosticArtifactBytes, 1_024);

        return new SettingsValidationResult(v);
    }

    private static void retryPolicy(List<SettingsValidationResult.Violation> v, String prefix,
                                    AiRetryPolicy policy) {
        // Unbounded AI retries are a forbidden invariant: 1..10 attempts, hard.
        if (policy.maximumAttempts < 1 || policy.maximumAttempts > 10) {
            v.add(new SettingsValidationResult.Violation(prefix + "maximumAttempts",
                    "must be between 1 and 10 (unbounded AI retries are not allowed)"));
        }
        if (policy.initialBackoffMillis < 0) {
            v.add(new SettingsValidationResult.Violation(prefix + "initialBackoffMillis",
                    "must not be negative"));
        }
        if (policy.backoffMultiplier < 1.0) {
            v.add(new SettingsValidationResult.Violation(prefix + "backoffMultiplier",
                    "must be at least 1.0"));
        }
        if (policy.maximumBackoffMillis < policy.initialBackoffMillis) {
            v.add(new SettingsValidationResult.Violation(prefix + "maximumBackoffMillis",
                    "must be >= initialBackoffMillis"));
        }
    }

    private static void promptContains(List<SettingsValidationResult.Violation> v, String key,
                                       String prompt, String variable) {
        if (prompt == null || !prompt.contains(variable)) {
            v.add(new SettingsValidationResult.Violation(key,
                    "prompt template must contain " + variable));
        }
    }

    private static void positive(List<SettingsValidationResult.Violation> v, String key, long value) {
        if (value <= 0) {
            v.add(new SettingsValidationResult.Violation(key, "must be > 0 (was " + value + ")"));
        }
    }

    private static void atLeast(List<SettingsValidationResult.Violation> v, String key, long value,
                                long minimum) {
        if (value < minimum) {
            v.add(new SettingsValidationResult.Violation(key,
                    "must be >= " + minimum + " (was " + value + ")"));
        }
    }

    private static void ratio(List<SettingsValidationResult.Violation> v, String key, double value) {
        if (value < 0.0 || value > 1.0) {
            v.add(new SettingsValidationResult.Violation(key,
                    "must be between 0 and 1 (was " + value + ")"));
        }
    }

    private static void weight(List<SettingsValidationResult.Violation> v, String key, double value) {
        if (value < 0.0) {
            v.add(new SettingsValidationResult.Violation(key,
                    "weight must not be negative (was " + value + ")"));
        }
    }

    private static void require(List<SettingsValidationResult.Violation> v, String key,
                                boolean condition, String message) {
        if (!condition) {
            v.add(new SettingsValidationResult.Violation(key, message));
        }
    }
}
