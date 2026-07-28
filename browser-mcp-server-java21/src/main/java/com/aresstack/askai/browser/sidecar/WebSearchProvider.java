package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.WebSearchItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    /** Legacy extraction: every outbound link with text becomes a hit (kept for tests/special providers). */
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

        /** Same-provider paths that still lead OUT of the engine: its result redirect wrappers. */
        private static final String[] REDIRECT_WRAPPER_PREFIXES = {"/ck/", "/url", "/l/"};

        public List<WebSearchItem> extract(BrowserPageSnapshot page, List<BrowserLink> links) {
            String providerSite = siteOf(hostOf(page.getUrl()));
            List<WebSearchItem> organic = new ArrayList<WebSearchItem>();
            Set<String> seenUrls = new HashSet<String>();
            for (BrowserLink link : links) {
                if (organic.size() >= MAX_RESULTS) {
                    break;
                }
                String url = link.getUrl();
                if (link.getText().isEmpty() || !url.startsWith("http")) {
                    continue; // javascript:/mailto:/fragment links are never routes
                }
                String host = hostOf(url);
                if (siteOf(host).equals(providerSite) && !isRedirectWrapper(url, host)) {
                    continue; // the engine's own navigation: tabs, verticals, settings, sign-in
                }
                if (seenUrls.add(url)) {
                    organic.add(new WebSearchItem(String.valueOf(organic.size() + 1),
                            link.getText(), url, link.getText()));
                }
            }
            return organic;
        }

        private static boolean isRedirectWrapper(String url, String host) {
            String path = pathOf(url);
            for (String prefix : REDIRECT_WRAPPER_PREFIXES) {
                if (path.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        static String hostOf(String url) {
            int i = url == null ? -1 : url.indexOf("://");
            if (i < 0) {
                return "";
            }
            String rest = url.substring(i + 3);
            int slash = rest.indexOf('/');
            String host = slash < 0 ? rest : rest.substring(0, slash);
            int colon = host.indexOf(':');
            return (colon < 0 ? host : host.substring(0, colon)).toLowerCase(Locale.ROOT);
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

        /**
         * The comparable "site" of a host: the last two labels ({@code www.bing.com} → {@code bing.com}),
         * so provider subdomains (cn.bing.com, login.bing.com) count as provider-internal too. Literal
         * IPs stay as-is.
         */
        static String siteOf(String host) {
            if (host.isEmpty() || host.matches("[0-9.:]+")) {
                return host;
            }
            String[] labels = host.split("\\.");
            return labels.length <= 2 ? host
                    : labels[labels.length - 2] + "." + labels[labels.length - 1];
        }
    }
}
