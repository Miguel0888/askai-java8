package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The heuristic verdict trusts DOM signals + a text threshold; the model judge only rules on ambiguous pages. */
public class PageReadinessJudgeTest {

    private static BrowserPageReadiness probe(int textLen, boolean challenge, boolean consent, String excerpt) {
        return new BrowserPageReadiness("https://x/y", "T", textLen, excerpt,
                challenge, challenge ? "challenge:x" : "", consent, consent ? "candidate:x" : "");
    }

    @Test
    public void heuristicUsesDomSignalsThenTextThreshold() {
        HeuristicPageReadinessJudge h = new HeuristicPageReadinessJudge(48);
        assertEquals(PageReadinessJudge.Verdict.CAPTCHA, h.judge(probe(5, true, false, "one last step")));
        assertEquals(PageReadinessJudge.Verdict.COOKIE_BANNER, h.judge(probe(5, false, true, "accept?")));
        assertEquals(PageReadinessJudge.Verdict.READABLE, h.judge(probe(500, false, false, "article")));
        // The heuristic's blind spot: a thin, unlabelled challenge page is judged UNREADABLE (→ parked/skipped).
        assertEquals(PageReadinessJudge.Verdict.UNREADABLE,
                h.judge(probe(20, false, false, "verify you are human")));
    }

    @Test
    public void modelJudgeCatchesAnUndetectedCaptchaOnAThinPage() {
        // A model that reads the excerpt and recognises the verification wall the DOM selectors missed.
        PageReadinessModel model = new PageReadinessModel() {
            public String complete(String system, String user) {
                return user.toLowerCase().contains("verify you are human") ? "CAPTCHA" : "READABLE";
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(model, 48);
        assertEquals("thin page, no DOM signal → the model rules it a CAPTCHA",
                PageReadinessJudge.Verdict.CAPTCHA, j.judge(probe(20, false, false, "Verify you are human")));
    }

    @Test
    public void modelJudgeSkipsTheModelForAClearlyReadablePage() {
        // A model that would (wrongly) say CAPTCHA must NOT be consulted for a substantial content page.
        PageReadinessModel poison = new PageReadinessModel() {
            public String complete(String system, String user) {
                return "CAPTCHA";
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(poison, 48);
        assertEquals(PageReadinessJudge.Verdict.READABLE,
                j.judge(probe(ModelPageReadinessJudge.CLEARLY_READABLE_CHARS + 1, false, false, "long article")));
    }

    @Test
    public void modelIsConsultedForALongPageWhoseExcerptLooksLikeACookieWall() {
        // A long page (would otherwise fast-path to READABLE) whose excerpt opens with cookie wording must
        // still be classified by the model, so the banner text does not bleed into the read content.
        PageReadinessModel model = new PageReadinessModel() {
            public String complete(String system, String user) {
                return "COOKIE_BANNER";
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(model, 48);
        assertEquals(PageReadinessJudge.Verdict.COOKIE_BANNER,
                j.judge(probe(3000, false, false, "We use cookies to improve your experience. Accept all?")));
    }

    @Test
    public void modelFailureFallsBackToTheHeuristicThreshold() {
        PageReadinessModel dead = new PageReadinessModel() {
            public String complete(String system, String user) {
                return ""; // model unavailable
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(dead, 48);
        assertEquals(PageReadinessJudge.Verdict.UNREADABLE, j.judge(probe(10, false, false, "tiny")));
        assertEquals(PageReadinessJudge.Verdict.READABLE, j.judge(probe(60, false, false, "enough text here")));
    }
}
