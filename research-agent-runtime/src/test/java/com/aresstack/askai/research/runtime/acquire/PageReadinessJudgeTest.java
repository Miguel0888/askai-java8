package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The heuristic verdict trusts a terminal block first, then DOM signals + a text threshold; the model judge only rules on ambiguous pages. */
public class PageReadinessJudgeTest {

    private static BrowserPageReadiness probe(int textLen, boolean challenge, boolean consent, String excerpt) {
        return new BrowserPageReadiness("https://x/y", "T", textLen, excerpt,
                challenge, challenge ? "challenge:x" : "", consent, consent ? "candidate:x" : "");
    }

    private static final String CLOUDFLARE_1020 =
            "Access denied. You do not have access to www.researchgate.net. "
                    + "Error reference number: 1020. Cloudflare Ray ID: abc123.";

    @Test
    public void heuristicUsesDomSignalsThenTextThreshold() {
        HeuristicPageReadinessJudge h = new HeuristicPageReadinessJudge(48);
        assertEquals(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE,
                h.judge(probe(5, true, false, "one last step")));
        assertEquals(PageReadinessJudge.Verdict.CONSENT_REQUIRED, h.judge(probe(5, false, true, "accept?")));
        assertEquals(PageReadinessJudge.Verdict.READABLE, h.judge(probe(500, false, false, "article")));
        assertEquals(PageReadinessJudge.Verdict.UNREADABLE,
                h.judge(probe(20, false, false, "verify you are human")));
    }

    @Test
    public void aTerminalBlockWinsOverAChallengeFlagAndPlentyOfText() {
        // The source-10 / hang root cause: a Cloudflare 1020 page trips challenge=true AND has >min text, but it
        // is a TERMINAL block — it must be ACCESS_BLOCKED (never a solvable challenge, never readable).
        HeuristicPageReadinessJudge h = new HeuristicPageReadinessJudge(48);
        assertEquals(PageReadinessJudge.Verdict.ACCESS_BLOCKED,
                h.judge(probe(300, true, false, CLOUDFLARE_1020)));
        assertEquals(PageReadinessJudge.Verdict.ACCESS_BLOCKED,
                h.judge(probe(300, false, false, CLOUDFLARE_1020)));
    }

    @Test
    public void modelJudgeMapsAChallengeAnswerToInteractiveChallenge() {
        PageReadinessModel model = new PageReadinessModel() {
            public String complete(String system, String user) {
                return user.toLowerCase().contains("verify you are human") ? "INTERACTIVE_CHALLENGE" : "READABLE";
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(model, 48);
        assertEquals(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE,
                j.judge(probe(20, false, false, "Verify you are human")));
    }

    @Test
    public void modelJudgeDecidesATerminalBlockDeterministicallyWithoutAModelCall() {
        PageReadinessModel poison = new PageReadinessModel() {
            public String complete(String system, String user) {
                return "READABLE"; // would wrongly read the block page — must never be consulted
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(poison, 48);
        assertEquals(PageReadinessJudge.Verdict.ACCESS_BLOCKED,
                j.judge(probe(300, true, false, CLOUDFLARE_1020)));
    }

    @Test
    public void modelJudgeSkipsTheModelForAClearlyReadablePage() {
        PageReadinessModel poison = new PageReadinessModel() {
            public String complete(String system, String user) {
                return "INTERACTIVE_CHALLENGE";
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(poison, 48);
        assertEquals(PageReadinessJudge.Verdict.READABLE,
                j.judge(probe(ModelPageReadinessJudge.CLEARLY_READABLE_CHARS + 1, false, false, "long article")));
    }

    @Test
    public void modelIsConsultedForALongPageWhoseExcerptLooksLikeAConsentWall() {
        PageReadinessModel model = new PageReadinessModel() {
            public String complete(String system, String user) {
                return "CONSENT_REQUIRED";
            }
        };
        ModelPageReadinessJudge j = new ModelPageReadinessJudge(model, 48);
        assertEquals(PageReadinessJudge.Verdict.CONSENT_REQUIRED,
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
