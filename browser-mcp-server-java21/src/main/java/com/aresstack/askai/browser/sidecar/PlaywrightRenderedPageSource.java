package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.analysis.RenderedPageSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The productive, browser-backed {@link RenderedPageSource} for {@code web_search_prepare}. It reuses
 * the SAME engine navigation as {@code web_search} (via
 * {@link PlaywrightBrowserSession#navigateAndCaptureSearchEngines}) — one loop, one truth — and simply
 * collects every captured engine page plus the navigation metadata (provider hosts, per-engine
 * attempts, challenge states). It performs no extraction and calls no model; the model-free
 * {@code SearchLayoutRepairTools} decides organic-vs-repair per page.
 */
final class PlaywrightRenderedPageSource implements RenderedPageSource {

    private final PlaywrightBrowserSession session;

    PlaywrightRenderedPageSource(PlaywrightBrowserSession session) {
        this.session = session;
    }

    public EngineCapture capture(String query) {
        final List<Captured> pages = new ArrayList<Captured>();
        try {
            PlaywrightBrowserSession.EngineNavigation nav =
                    session.navigateAndCaptureSearchEngines(query,
                            new PlaywrightBrowserSession.CapturedPageConsumer() {
                                public boolean accept(RenderedPageDocument document, String host,
                                        List<LegacySearchEngineAttemptResult> attempts) {
                                    pages.add(new Captured(document, host));
                                    return false; // collect EVERY engine's page for repair, in order
                                }
                            });
            return new EngineCapture(pages, nav.providerHosts, nav.attempts, nav.challenges);
        } catch (BrowserException engineUnavailable) {
            // No engine reachable / no provider configured: an empty capture — prepare reports FAILED
            // and the runtime falls through its existing engine policy.
            return new EngineCapture(pages, Collections.<String>emptyList(),
                    Collections.<LegacySearchEngineAttemptResult>emptyList(),
                    Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>
                            emptyList());
        }
    }
}
