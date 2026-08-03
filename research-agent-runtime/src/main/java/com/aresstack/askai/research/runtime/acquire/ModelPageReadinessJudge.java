package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import java.util.Locale;

/**
 * A model-backed readiness judge ("the AI scans the page"). It still trusts an unambiguous DOM signal first
 * (a matched challenge or consent control), and short-circuits a clearly-substantial content page WITHOUT a
 * model call; only for an AMBIGUOUS page — little text and no DOM signal, exactly where the heuristic wrongly
 * parks an undetected CAPTCHA/paywall — does it ask the model to classify it. Any model failure falls back to
 * the heuristic threshold, so the search never depends on the model being available.
 */
public final class ModelPageReadinessJudge implements PageReadinessJudge {

    /** A page with at least this much body text and no DOM signal is read without consulting the model. */
    static final int CLEARLY_READABLE_CHARS = 400;

    private static final String SYSTEM =
            "You inspect a web page we just opened to read it as a research source. Decide what the page is. "
                    + "Answer with EXACTLY ONE word, no punctuation:\n"
                    + "READABLE - real article/content we can read;\n"
                    + "COOKIE_BANNER - a cookie/consent wall blocks the content;\n"
                    + "CAPTCHA - a human-verification/challenge/\"are you human\" page;\n"
                    + "UNREADABLE - error, empty, login/paywall, or otherwise not usable.";

    private final PageReadinessModel model;
    private final int minReadableChars;

    public ModelPageReadinessJudge(PageReadinessModel model, int minReadableChars) {
        this.model = model;
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
        if (probe.textLength >= CLEARLY_READABLE_CHARS && !looksSuspicious(probe.excerpt)) {
            return Verdict.READABLE; // clearly a content page, nothing wall-like in the excerpt — no model call
        }
        Verdict fromModel = classifyWithModel(probe);
        if (fromModel != null) {
            return fromModel;
        }
        // Model unavailable/uncertain: fall back to the deterministic threshold.
        return probe.textLength >= minReadableChars ? Verdict.READABLE : Verdict.UNREADABLE;
    }

    /**
     * A cheap keyword pre-filter over the excerpt: a page whose visible text opens with cookie/consent or
     * verification wording is worth a model classification EVEN when it is long, because a wall the DOM
     * selectors missed otherwise slips through the "clearly readable" fast-path into the read content.
     */
    private static boolean looksSuspicious(String excerpt) {
        if (excerpt == null || excerpt.isEmpty()) {
            return false;
        }
        String e = excerpt.toLowerCase(Locale.ROOT);
        String[] markers = {
                "cookie", "consent", "akzeptier", "zustimm", "we use cookies", "datenschutz", "privacy",
                "captcha", "verify you are human", "are you human", "not a robot", "human verification",
                "just a moment", "enable javascript", "cloudflare", "einwillig", "einverstanden"};
        for (String m : markers) {
            if (e.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private Verdict classifyWithModel(BrowserPageReadiness probe) {
        String user = "URL: " + probe.url + "\nTitle: " + probe.title
                + "\nVisible text length: " + probe.textLength + " characters\n"
                + "Excerpt:\n" + probe.excerpt;
        String answer;
        try {
            answer = model.complete(SYSTEM, user);
        } catch (RuntimeException ex) {
            return null; // never let a model problem abort the visit
        }
        if (answer == null) {
            return null;
        }
        String a = answer.toUpperCase(Locale.ROOT);
        // Order matters: check the more specific obstructions before the generic READABLE substring.
        if (a.contains("CAPTCHA")) {
            return Verdict.CAPTCHA;
        }
        if (a.contains("COOKIE") || a.contains("CONSENT")) {
            return Verdict.COOKIE_BANNER;
        }
        if (a.contains("UNREADABLE")) {
            return Verdict.UNREADABLE;
        }
        if (a.contains("READABLE")) {
            return Verdict.READABLE;
        }
        return null; // unrecognised answer → heuristic fallback
    }
}
