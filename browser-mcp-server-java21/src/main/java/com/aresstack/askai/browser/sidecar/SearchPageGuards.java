package com.aresstack.askai.browser.sidecar;

import java.util.Arrays;
import java.util.List;

/**
 * The SERP guard scripts: consent dismissal and manual-challenge detection, adapted from MainframeMate's
 * {@code CookieBannerDismisser} onto Playwright. Both run inside the page via {@code page.evaluate} and are
 * deliberately conservative:
 * <ul>
 * <li>Consent clicks only UNAMBIGUOUSLY positive controls — known CMP accept buttons (OneTrust, Cookiebot,
 *     Quantcast, Usercentrics) or visible buttons whose text is a positive consent phrase. There is NO
 *     "first button in the cookie container" heuristic: that could hit "Settings" or "Only necessary".</li>
 * <li>Challenge detection looks for CAPTCHA/"one last step" markers and must run BEFORE any generic
 *     content fallback — a challenge page is never a readable search result.</li>
 * </ul>
 * These scripts are used on SEARCH pages only; visited target pages are captured as-is.
 */
final class SearchPageGuards {

    private SearchPageGuards() {
    }

    /** Known CMP accept-all buttons (ported from MainframeMate; unambiguous selectors only). */
    static final List<String> CONSENT_SELECTORS = Arrays.asList(
            // OneTrust (very common)
            "#onetrust-accept-btn-handler",
            // Cookiebot
            "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll",
            // Quantcast / CMP
            ".qc-cmp2-summary-buttons button[mode='primary']",
            // Usercentrics
            "#uc-btn-accept-banner",
            // Bing's own consent banner
            "#bnp_btn_accept",
            // Generic accept buttons inside cookie/consent containers (still ACCEPT-specific)
            "[class*='cookie'] button[class*='accept']",
            "[class*='consent'] button[class*='accept']",
            "[id*='cookie'] button[id*='accept']",
            "[id*='consent'] button[id*='accept']",
            "button[data-action='accept']",
            "button[data-action='accept-all']",
            "button[data-action='acceptAll']");

    /** Visible button texts that are an unambiguous consent (lower-cased containment match). */
    static final List<String> POSITIVE_CONSENT_TEXTS = Arrays.asList(
            "accept all", "allow all", "i agree",
            "alle akzeptieren", "alle annehmen", "alle zulassen", "zustimmen", "akzeptieren");

    /** Visible texts that mark a manual challenge page. */
    static final List<String> CHALLENGE_TEXTS = Arrays.asList(
            "noch ein letzter schritt", "one last step", "verify you are human",
            "unusual traffic", "complete the challenge", "captcha",
            "bestätigen sie, dass sie ein mensch sind");

    /** DOM markers of a challenge page. */
    static final List<String> CHALLENGE_SELECTORS = Arrays.asList(
            "iframe[src*='captcha']", "iframe[title*='challenge']", "#challenge-form",
            "[class*='captcha']", "[id*='captcha']",
            // Bing's "one last step" challenge container
            "#b_rrsr", "iframe[src*='turnstile']");

    /** The consent-dismiss script: returns 'clicked:…' or 'none'. */
    static String consentDismissScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const selectors = ").append(jsArray(CONSENT_SELECTORS)).append(";\n");
        sb.append("  for (const s of selectors) {\n");
        sb.append("    try {\n");
        sb.append("      const el = document.querySelector(s);\n");
        sb.append("      if (el && el.offsetParent !== null) { el.click(); return 'clicked:' + s; }\n");
        sb.append("    } catch (e) {}\n");
        sb.append("  }\n");
        sb.append("  const positives = ").append(jsArray(POSITIVE_CONSENT_TEXTS)).append(";\n");
        sb.append("  const buttons = document.querySelectorAll(\"button, a[role='button'], [class*='btn']\");\n");
        sb.append("  for (let i = 0; i < buttons.length && i < 80; i++) {\n");
        sb.append("    const txt = (buttons[i].innerText || '').toLowerCase().trim();\n");
        sb.append("    if (!txt || buttons[i].offsetParent === null) continue;\n");
        sb.append("    if (positives.some(p => txt === p || txt.startsWith(p))) {\n");
        sb.append("      buttons[i].click(); return 'clicked-text:' + txt;\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
    }

    /** The challenge-detection script: returns 'challenge:…' or 'none'. */
    static String challengeDetectScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const selectors = ").append(jsArray(CHALLENGE_SELECTORS)).append(";\n");
        sb.append("  for (const s of selectors) {\n");
        sb.append("    try {\n");
        sb.append("      const el = document.querySelector(s);\n");
        sb.append("      if (el && (el.offsetParent !== null || el.clientHeight > 0)) return 'challenge:' + s;\n");
        sb.append("    } catch (e) {}\n");
        sb.append("  }\n");
        sb.append("  const texts = ").append(jsArray(CHALLENGE_TEXTS)).append(";\n");
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
