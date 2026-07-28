package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the guard-script policy: consent clicks only UNAMBIGUOUSLY positive controls (no
 * first-button-in-container guessing — that could hit "Settings" or "Only necessary"), and the
 * challenge detection knows the required texts and selectors. The lists come from the settings
 * (single default origin: LegacyBrowserSearchDefaults) — the guards hold no constants of their own.
 */
public class SearchPageGuardsTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    @Test
    public void consentScriptHasNoFirstButtonGuessingAndOnlyPositiveTexts() {
        String script = SearchPageGuards.consentDismissScript(defaults.consent);
        assertFalse("no dangerous first-button heuristic", script.contains(":first-of-type"));
        for (String selector : defaults.consent.positiveButtonSelectors) {
            assertFalse("selector must be accept-specific: " + selector,
                    selector.contains(":first-of-type"));
        }
        for (String required : new String[]{"accept all", "allow all", "i agree",
                "alle akzeptieren", "alle annehmen", "alle zulassen", "zustimmen"}) {
            assertTrue("positive text missing: " + required,
                    defaults.consent.positiveButtonTexts.contains(required));
        }
        assertTrue(script.contains("clicked"));
    }

    @Test
    public void challengeScriptDetectsTheRequiredTextsAndSelectors() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        for (String text : new String[]{"noch ein letzter schritt", "one last step",
                "verify you are human", "unusual traffic", "complete the challenge", "captcha"}) {
            assertTrue("challenge text missing: " + text,
                    defaults.captcha.challengeTexts.contains(text));
        }
        for (String selector : new String[]{"iframe[src*='captcha']", "iframe[title*='challenge']",
                "#challenge-form", "[class*='captcha']", "[id*='captcha']"}) {
            assertTrue("challenge selector missing: " + selector,
                    defaults.captcha.challengeSelectors.contains(selector));
        }
        assertTrue(script.contains("challenge"));
    }
}
