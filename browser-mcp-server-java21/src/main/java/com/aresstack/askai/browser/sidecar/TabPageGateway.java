package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;

/**
 * The narrow seam between the {@link TabManager}'s pure scheduling logic and the actual pages. In production a
 * {@code PlaywrightTabGateway} opens real background pages on the Playwright thread; in scheduler tests a fake
 * gateway returns scripted handles and readiness — so limits, stagger, overtaking, timeouts, cancel and late-
 * result handling are all verified without a browser. Every method is called by the manager from a single
 * thread; handles are opaque tokens the manager stores per tab and hands back.
 */
interface TabPageGateway {

    /**
     * Begin navigating a FRESH page to {@code url}, returning only once navigation has COMMITTED (headers in),
     * not once fully loaded — the page keeps rendering in the background afterwards. The returned handle
     * identifies this page for later probing/reading/closing.
     */
    Object startNavigation(String url) throws BrowserException;

    /** A non-blocking readiness probe over the page behind {@code handle}. */
    ReadinessProbe probe(Object handle) throws BrowserException;

    /** Atomically read the settled page state (final URL, title, text, anchors) behind {@code handle}. */
    PlaywrightPageState read(Object handle) throws BrowserException;

    /** Close/discard the page behind {@code handle}; must tolerate an already-closed page. */
    void closeTab(Object handle);
}
