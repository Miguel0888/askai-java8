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

    /** Idempotent: page → context → browser → Playwright (and with it the GraalJS driver child). */
    void close();
}
