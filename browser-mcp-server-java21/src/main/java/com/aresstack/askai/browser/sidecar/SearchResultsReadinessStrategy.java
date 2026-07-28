package com.aresstack.askai.browser.sidecar;

import java.util.Arrays;
import java.util.List;

/**
 * Readiness for engine RESULT pages, where {@link GenericContentReadinessStrategy} fires too early (the search
 * header and chrome stabilise before the JS-injected result list arrives). It watches for three interpretable
 * end-states via CSS selectors: a populated result container ({@link ReadinessLabel#RESULTS}), an explicit
 * no-results notice ({@link ReadinessLabel#NO_RESULTS}), or a consent/captcha/challenge wall
 * ({@link ReadinessLabel#CHALLENGE}). Selectors cover Bing and the DuckDuckGo html/lite endpoints used as
 * fallbacks; when none matches yet it stays PENDING and the per-tab deadline remains the backstop. The generic
 * strategy is passed in and consulted last, so an unknown engine layout still settles on content stability
 * instead of hanging until timeout.
 */
final class SearchResultsReadinessStrategy implements PageReadinessStrategy {

    // Result containers: Bing (#b_results, .b_algo), DuckDuckGo html/lite (.results, .result, .web-result).
    private static final List<String> RESULT_SELECTORS = Arrays.asList(
            "#b_results .b_algo", "ol#b_results > li", ".b_algo",
            ".web-result", ".results_links", "table.result-link", "#links .result");
    // Explicit "no results" states.
    private static final List<String> NO_RESULTS_SELECTORS = Arrays.asList(
            ".b_no", "#b_results .b_no", ".no-results", ".msg--noresults");
    // Consent / captcha / bot-challenge walls.
    private static final List<String> CHALLENGE_SELECTORS = Arrays.asList(
            "#bnp_container", ".consent", "form[action*='consent']",
            "#challenge-form", "iframe[src*='captcha']", "iframe[title*='challenge']");

    private final PageReadinessStrategy genericFallback;

    SearchResultsReadinessStrategy(PageReadinessStrategy genericFallback) {
        this.genericFallback = genericFallback;
    }

    @Override
    public ReadinessLabel inspect(ReadinessProbe probe, ReadinessState state, PageReadinessPolicy policy) {
        if (probe.anySelectorPresent(CHALLENGE_SELECTORS)) {
            return ReadinessLabel.CHALLENGE;
        }
        if (probe.anySelectorPresent(RESULT_SELECTORS)) {
            return ReadinessLabel.RESULTS;
        }
        if (probe.anySelectorPresent(NO_RESULTS_SELECTORS)) {
            return ReadinessLabel.NO_RESULTS;
        }
        // Unknown engine layout / results not in yet: fall back to generic content stability as the backstop.
        return genericFallback.inspect(probe, state, policy);
    }
}
