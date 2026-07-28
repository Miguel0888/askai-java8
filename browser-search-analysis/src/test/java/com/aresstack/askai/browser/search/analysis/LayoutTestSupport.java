package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.AiRetryPolicy;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;

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
}
