package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.AiRetryPolicy;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.MechanicalConfidenceOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shared builders for the A4 tests: settings variants (tight diagnostics, enabled/disabled AI
 * resolver, tuned retry policy) derived from {@link LegacyBrowserSearchDefaults} so the tests never
 * hand-assemble a whole settings tree.
 */
final class LayoutTestSupport {

    private LayoutTestSupport() {
    }

    static LegacyBrowserSearchSettings withDiagnostics(LegacyBrowserSearchSettings base,
                                                       SearchDiagnosticsSettings diagnostics) {
        return new LegacyBrowserSearchSettings(base.navigation, base.consent, base.captcha,
                base.readiness, base.analysis, base.visualAnalysis, base.extraction,
                base.aiLayoutResolver, base.reranker, diagnostics);
    }

    static LegacyBrowserSearchSettings withAiLayoutResolver(LegacyBrowserSearchSettings base,
                                                            AiLayoutResolverSettings ai) {
        return new LegacyBrowserSearchSettings(base.navigation, base.consent, base.captcha,
                base.readiness, base.analysis, base.visualAnalysis, base.extraction, ai,
                base.reranker, base.diagnostics);
    }

    /** Default diagnostics with a specific text-excerpt cap. */
    static SearchDiagnosticsSettings diagnosticsWithExcerptCap(int cap) {
        return new SearchDiagnosticsSettings(true, true, true, true, true, false, true, true, true,
                true, cap, 262_144, false);
    }

    /** Default diagnostics with raw model responses toggled and a byte cap. */
    static SearchDiagnosticsSettings diagnostics(boolean storeRaw, int excerptCap, int byteCap,
                                                 boolean redactUrls) {
        return new SearchDiagnosticsSettings(true, true, true, true, true, storeRaw, true, true,
                true, true, excerptCap, byteCap, redactUrls);
    }

    /** An AI resolver settings block copying the default prompts but with explicit enabled/profile. */
    static AiLayoutResolverSettings aiSettings(boolean enabled, String modelProfileId,
                                               AiRetryPolicy retryPolicy) {
        AiLayoutResolverSettings defaults = LegacyBrowserSearchDefaults.create().aiLayoutResolver;
        return new AiLayoutResolverSettings(enabled, modelProfileId, defaults.reasoningEffort,
                defaults.temperature, defaults.maximumOutputTokens, defaults.systemPromptTemplate,
                defaults.userPromptTemplate, retryPolicy);
    }

    /** A retry policy with a specific maximum-attempts count, otherwise the productive defaults. */
    static AiRetryPolicy retryPolicy(int maximumAttempts) {
        return new AiRetryPolicy(maximumAttempts, 0, 1.0, 0, true, true, true, true, true, true,
                true, true);
    }

    /** A minimal container candidate with an id and parent — geometry/scores neutral. */
    static SearchPageContainerCandidate candidate(String id, String parent) {
        return new SearchPageContainerCandidate(id, parent, "div", "", Collections.<String>emptyList(),
                "", 100, 60, 1, 3, 0, 0, 3, new RenderedBox(0, 0, 100, 100), 1.0, false, 0.1, 0.1,
                0, 0, "", false, "div(a)", 2, Collections.<com.aresstack.askai.browser.search.layout
                        .SearchPageSignalScore>emptyList(), 1.0, "");
    }

    /** A synthetic low-confidence artifact bound to a snapshot, exposing the given candidates. */
    static SearchPageAnalysisArtifact artifactOf(String snapshotId,
                                                 SearchPageContainerCandidate... candidates) {
        List<SearchPageContainerCandidate> list =
                new ArrayList<SearchPageContainerCandidate>(Arrays.asList(candidates));
        List<String> preferred = new ArrayList<String>();
        for (SearchPageContainerCandidate candidate : list) {
            preferred.add(candidate.containerId);
        }
        return new SearchPageAnalysisArtifact("analysis-" + snapshotId + "-1", snapshotId, 1L, "fp",
                "q", EngineFamily.GENERIC, "https://engine.example/find?q=q", "SERP",
                MechanicalConfidenceOutcome.LOW_CONFIDENCE, 0.2, preferred, list,
                Collections.<String>emptyList(), Collections.<String>emptyList(), "digest");
    }
}
