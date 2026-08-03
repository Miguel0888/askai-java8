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
        // A TERMINAL block wins over any challenge/consent DOM flag: a Cloudflare 1020 page often also trips a
        // challenge marker, but there is nothing to solve — it must NOT wait for the user and must NOT be read.
        if (AccessBlockSignals.isBlocked(probe)) {
            return Verdict.ACCESS_BLOCKED;
        }
        if (probe.challengePresent) {
            return Verdict.INTERACTIVE_CHALLENGE;
        }
        if (probe.consentPresent) {
            return Verdict.CONSENT_REQUIRED;
        }
        return probe.textLength >= minReadableChars ? Verdict.READABLE : Verdict.UNREADABLE;
    }
}
