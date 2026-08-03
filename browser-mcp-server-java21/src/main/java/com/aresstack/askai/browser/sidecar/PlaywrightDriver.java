package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;

/**
 * The narrow seam between the {@link PlaywrightBrowserSession} and the Playwright runtime. Everything below
 * this interface (official Playwright Java API, playwright4j's GraalJS driver child process, Chromium
 * lifecycle) may be complex; above it only {@link PlaywrightPageState} values flow.
 *
 * <p>Deliberate deviations from a fuller driver surface: link following is resolved to the anchor's absolute
 * href by the session (which owns the link-id mapping) and goes through {@link #open}, so pre/post URL-policy
 * checks apply identically; search is a session concern behind {@link WebSearchProvider}, because
 * playwright4j brings no search engine integration.</p>
 */
interface PlaywrightDriver extends AutoCloseable {

    /** Navigate the single page to the URL and return the resulting state (final URL after redirects). */
    PlaywrightPageState open(String url) throws BrowserException;

    /** Re-read the current page without navigating. */
    PlaywrightPageState current() throws BrowserException;

    /** Browser history back. Fails readably when there is no previous page. */
    PlaywrightPageState back() throws BrowserException;

    /**
     * Capture the CURRENT page as a neutral {@link com.aresstack.askai.browser.render.RenderedPageDocument}
     * (container hierarchy, text/link statistics, geometry, colors) for the mechanical SERP analysis.
     * @param domainKeys the session's domain-key resolver (link domain classification)
     * @param snapshotGeneration monotonic per session; stale-reference guard for container/link ids
     * @return the document, or null when this driver cannot capture (tests provide their own)
     */
    default com.aresstack.askai.browser.render.RenderedPageDocument captureRenderedPage(
            com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
            long snapshotGeneration) throws BrowserException {
        return null;
    }

    // ------------------------------------------------------------------ search-page guards (SERP only)
    // These stay driver-level so SERP details (consent, challenge DOM) never leak into the research loop.

    /**
     * Try to dismiss a consent banner on the CURRENT page by clicking one UNAMBIGUOUSLY positive button
     * ("Accept all", "Alle akzeptieren", …) — never a first-button-in-container guess.
     * @return {@code "clicked:…"} when a button was clicked, {@code "none"} otherwise.
     */
    default String tryDismissConsent() {
        return "none";
    }

    /** @return true when the CURRENT page shows a manual challenge (CAPTCHA / "one last step"). */
    default boolean challengePresent() {
        return false;
    }

    /**
     * @return the challenge marker that matched on the CURRENT page ({@code "challenge:<selector>"} or
     * {@code "challenge-text:<text>"}), or {@code "none"}. A richer form of {@link #challengePresent}.
     */
    default String challengeMarker() {
        return challengePresent() ? "challenge:?" : "none";
    }

    /**
     * @return a hint at a consent/cookie dismiss control on the CURRENT page WITHOUT clicking it
     * ({@code "candidate:<selector>"} / {@code "candidate-text:<label>"}), or {@code "none"}.
     */
    default String consentCandidate() {
        return "none";
    }

    /**
     * Park the current (challenge) page: keep it open and bring it to the user's attention ONCE, then
     * continue on a fresh page. At most one page is parked at a time.
     * @return false when parking is not supported or a page is already parked.
     */
    default boolean parkChallenge() {
        return false;
    }

    /** @return true while a parked challenge page still shows the challenge (false = resolved or none). */
    default boolean parkedChallengeStillPresent() {
        return false;
    }

    /** Close the parked challenge page, if any. Idempotent. */
    default void closeParkedChallenge() {
    }

    /**
     * Install (idempotent) + render the Research HUD overlay on the CURRENT page from a serialized
     * {@code ResearchHudState}. Buttons feed {@link #pollHudCommands()}. No-op default returns 'unsupported'.
     */
    default String renderHud(String stateLine) {
        return "unsupported";
    }

    /** Drain the buffered HUD commands from the overlay (newline-separated), or "" when none. */
    default String pollHudCommands() {
        return "";
    }

    /** Idempotent: page → context → browser → Playwright (and with it the GraalJS driver child). */
    void close();
}
