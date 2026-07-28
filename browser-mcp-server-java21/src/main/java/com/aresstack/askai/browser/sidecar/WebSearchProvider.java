package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.WebSearchItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * The "street sign" extraction (default): only plausible ORGANIC result links become hits, so the agent
     * gets a short list of routes to real target websites — never the search engine's own navigation
     * (Videos/Shopping/Maps tabs, settings, sign-in). Provider-internal links are dropped unless they are
     * known redirect wrappers (Bing {@code /ck/}, Google {@code /url}, DuckDuckGo {@code /l/}) — those ARE
     * the organic links on engines that wrap their results. The list is deduplicated and capped so it stays
     * digestible for small models. An EMPTY result means "this engine gave us no routes" — the session then
     * tries its fallback engines and only degrades to the legacy all-links extraction as the last resort.
     */
    final class OrganicResultSearchProvider implements WebSearchProvider {

        /** A short route list beats a full crawl frontier (small-model context budget). */
        static final int MAX_RESULTS = 20;

        private final com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys;

        OrganicResultSearchProvider(com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys) {
            this.domainKeys = domainKeys;
        }

        public List<WebSearchItem> extract(BrowserPageSnapshot page, List<BrowserLink> links) {
            com.aresstack.askai.browser.domain.DomainIdentity provider =
                    domainKeys.resolve(page.getUrl());
            List<WebSearchItem> organic = new ArrayList<WebSearchItem>();
            Set<String> seenUrls = new HashSet<String>();
            for (BrowserLink link : links) {
                if (organic.size() >= MAX_RESULTS) {
                    break;
                }
                String rawUrl = link.getUrl();
                if (link.getText().isEmpty() || !rawUrl.startsWith("http")) {
                    continue; // javascript:/mailto:/fragment links are never routes
                }
                // Redirect wrappers are resolved BEFORE any domain judgement; an unresolvable wrapper is
                // discarded in a controlled way, never misclassified by the engine's wrapper host.
                SearchRedirectResolver.Resolution resolution = SearchRedirectResolver.resolve(rawUrl);
                if (resolution.getStatus() == SearchRedirectResolver.Status.UNRESOLVED) {
                    continue;
                }
                boolean wrapped = resolution.getStatus() == SearchRedirectResolver.Status.RESOLVED;
                String effectiveUrl = wrapped ? resolution.getTargetUrl() : rawUrl;
                // Typed link classification on the RESOLVED target: verticals, pagination, refinements,
                // account/legal and ads are MODELED but never candidates; only ORGANIC_RESULT passes.
                if (SearchPageLinkType.classify(effectiveUrl, link.getText(), provider, domainKeys)
                        != SearchPageLinkType.ORGANIC_RESULT) {
                    continue;
                }
                // The NAVIGATION target stays the raw URL (the engine expects its wrapper to be followed);
                // the resolved target only drives the domain judgement above.
                if (seenUrls.add(effectiveUrl)) {
                    organic.add(new WebSearchItem(String.valueOf(organic.size() + 1),
                            link.getText(), rawUrl, link.getText()));
                }
            }
            return organic;
        }

        static String pathOf(String url) {
            int i = url.indexOf("://");
            if (i < 0) {
                return "";
            }
            String rest = url.substring(i + 3);
            int slash = rest.indexOf('/');
            return slash < 0 ? "/" : rest.substring(slash);
        }
    }
}
