package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.search.CaptchaHandlingSettings;
import com.aresstack.askai.browser.search.ConsentHandlingSettings;

import java.util.List;

/**
 * The SERP guard scripts: consent dismissal and manual-challenge detection, adapted from MainframeMate's
 * {@code CookieBannerDismisser} onto Playwright. Both run inside the page via {@code page.evaluate} and are
 * deliberately conservative:
 * <ul>
 * <li>Consent clicks only UNAMBIGUOUSLY positive controls — the configured CMP accept selectors or visible
 *     buttons whose text is a configured positive consent phrase. There is NO "first button in the cookie
 *     container" heuristic, and no configuration can create one: only selectors/texts are configurable,
 *     the click policy itself is fixed.</li>
 * <li>Challenge detection looks for the configured CAPTCHA/"one last step" markers and must run BEFORE any
 *     generic content fallback — a challenge page is never a readable search result.</li>
 * </ul>
 * Selector/text lists and timings come EXCLUSIVELY from the settings ({@code LegacyBrowserSearchDefaults}
 * being their single default origin) — this class holds no constants of its own. The scripts are used on
 * SEARCH pages only; visited target pages are captured as-is.
 */
final class SearchPageGuards {

    private SearchPageGuards() {
    }

    /** The consent-dismiss script: returns 'clicked:…' or 'none'. */
    static String consentDismissScript(ConsentHandlingSettings consent) {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const selectors = ").append(jsArray(consent.positiveButtonSelectors)).append(";\n");
        sb.append("  for (const s of selectors) {\n");
        sb.append("    try {\n");
        sb.append("      const el = document.querySelector(s);\n");
        if (consent.focusBeforeClick) {
            sb.append("      if (el && el.offsetParent !== null) { el.focus(); el.click(); return 'clicked:' + s; }\n");
        } else {
            sb.append("      if (el && el.offsetParent !== null) { el.click(); return 'clicked:' + s; }\n");
        }
        sb.append("    } catch (e) {}\n");
        sb.append("  }\n");
        sb.append("  const positives = ").append(jsArray(consent.positiveButtonTexts)).append(";\n");
        sb.append("  const buttons = document.querySelectorAll(\"button, a[role='button'], [class*='btn']\");\n");
        sb.append("  for (let i = 0; i < buttons.length && i < 80; i++) {\n");
        sb.append("    const txt = (buttons[i].innerText || '').toLowerCase().trim();\n");
        sb.append("    if (!txt || buttons[i].offsetParent === null) continue;\n");
        sb.append("    if (positives.some(p => txt === p || txt.startsWith(p))) {\n");
        if (consent.focusBeforeClick) {
            sb.append("      buttons[i].focus(); buttons[i].click(); return 'clicked-text:' + txt;\n");
        } else {
            sb.append("      buttons[i].click(); return 'clicked-text:' + txt;\n");
        }
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
    }

    /** The challenge-detection script: returns 'challenge:…' or 'none'. */
    static String challengeDetectScript(CaptchaHandlingSettings captcha) {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const selectors = ").append(jsArray(captcha.challengeSelectors)).append(";\n");
        sb.append("  for (const s of selectors) {\n");
        sb.append("    try {\n");
        sb.append("      const el = document.querySelector(s);\n");
        sb.append("      if (el && (el.offsetParent !== null || el.clientHeight > 0)) return 'challenge:' + s;\n");
        sb.append("    } catch (e) {}\n");
        sb.append("  }\n");
        sb.append("  const texts = ").append(jsArray(captcha.challengeTexts)).append(";\n");
        sb.append("  const body = (document.body ? document.body.innerText : '').toLowerCase();\n");
        sb.append("  for (const t of texts) { if (body.includes(t)) return 'challenge-text:' + t; }\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(values.get(i).replace("\\", "\\\\").replace("'", "\\'")).append('\'');
        }
        return sb.append(']').toString();
    }
}
