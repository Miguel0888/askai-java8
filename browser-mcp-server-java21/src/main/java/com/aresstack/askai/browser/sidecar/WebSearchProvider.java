package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.WebSearchItem;

import java.util.ArrayList;
import java.util.List;

/**
 * playwright4j brings no search-engine integration, so {@code web_search} on the Playwright backend is plain
 * browser NAVIGATION to a configured provider URL ({@code {query}} placeholder, no hardcoded account) — the
 * result extraction from the loaded provider page sits behind this interface. Without a configured provider
 * URL the session reports search honestly as unavailable; there is no hidden default engine and the search
 * page itself is never a source capture.
 */
interface WebSearchProvider {

    /** Extract structured results from the loaded provider result page. */
    List<WebSearchItem> extract(BrowserPageSnapshot page, List<BrowserLink> links);

    /** Default extraction: the result page's outbound links (non-empty text) become the hits. */
    final class LinkListSearchProvider implements WebSearchProvider {
        public List<WebSearchItem> extract(BrowserPageSnapshot page, List<BrowserLink> links) {
            List<WebSearchItem> items = new ArrayList<WebSearchItem>();
            int id = 0;
            for (BrowserLink link : links) {
                if (link.getText().isEmpty()) {
                    continue;
                }
                id++;
                items.add(new WebSearchItem(String.valueOf(id), link.getText(), link.getUrl(),
                        link.getText()));
            }
            return items;
        }
    }
}
