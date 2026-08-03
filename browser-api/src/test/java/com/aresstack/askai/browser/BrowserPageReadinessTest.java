package com.aresstack.askai.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The readability probe renders to a typed block and parses back losslessly (host ↔ sidecar wire). */
public class BrowserPageReadinessTest {

    @Test
    public void renderThenParseRoundTrips() {
        BrowserPageReadiness p = new BrowserPageReadiness(
                "https://example.org/a", "Waveguide displays", 5321,
                "Smart glasses use waveguide displays to project images.",
                false, "none", true, "candidate-text:alle akzeptieren");
        BrowserPageReadiness back = BrowserPageReadiness.parse(p.render());
        assertEquals("https://example.org/a", back.url);
        assertEquals("Waveguide displays", back.title);
        assertEquals(5321, back.textLength);
        assertFalse(back.challengePresent);
        assertTrue(back.consentPresent);
        assertEquals("candidate-text:alle akzeptieren", back.consentCandidate);
        assertTrue(back.excerpt.startsWith("Smart glasses use waveguide"));
    }

    @Test
    public void aChallengeProbeParsesBack() {
        BrowserPageReadiness p = new BrowserPageReadiness(
                "https://blocked.example/x", "One last step", 40, "verify you are human",
                true, "challenge-text:verify you are human", false, "none");
        BrowserPageReadiness back = BrowserPageReadiness.parse(p.render());
        assertTrue(back.challengePresent);
        assertEquals("challenge-text:verify you are human", back.challengeMarker);
        assertFalse(back.consentPresent);
    }

    @Test
    public void excerptIsBounded() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append('x');
        }
        String excerpt = BrowserPageReadiness.excerptOf(big.toString());
        assertEquals(BrowserPageReadiness.EXCERPT_LIMIT, excerpt.length());
    }
}
