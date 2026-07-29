package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedPageDocument;

import java.util.List;

/**
 * The source of captured engine pages for {@code web_search_prepare} — the seam that keeps the repair
 * tools independent of Playwright. Production supplies a browser-backed implementation that navigates
 * the configured engines and captures neutral {@link RenderedPageDocument}s; tests supply synthetic
 * pages. The source itself never calls a model.
 */
public interface RenderedPageSource {

    /** One captured engine page: the neutral rendered document and the engine host it came from. */
    final class Captured {
        public final RenderedPageDocument document;
        public final String engineHost;

        public Captured(RenderedPageDocument document, String engineHost) {
            this.document = document;
            this.engineHost = engineHost == null ? "" : engineHost;
        }
    }

    /** Capture the engine result pages for the query, in engine order. Never null. */
    List<Captured> capture(String query);
}
