package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

/**
 * The deterministic readiness verdict: trust the DOM signals (challenge / consent) first, then treat a page
 * with at least {@code minReadableChars} of body text as readable, everything else as unreadable. Cheap and
 * model-free; misses obstructions the SERP-tuned selectors do not match (that is what
 * {@link ModelPageReadinessJudge} adds).
 */
public final class HeuristicPageReadinessJudge implements PageReadinessJudge {

    private final int minReadableChars;

    public HeuristicPageReadinessJudge(int minReadableChars) {
        this.minReadableChars = minReadableChars;
    }

    @Override
    public Verdict judge(BrowserPageReadiness probe) {
        if (probe.challengePresent) {
            return Verdict.CAPTCHA;
        }
        if (probe.consentPresent) {
            return Verdict.COOKIE_BANNER;
        }
        return probe.textLength >= minReadableChars ? Verdict.READABLE : Verdict.UNREADABLE;
    }
}
