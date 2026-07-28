package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.WebSearchItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The "street sign" search extraction: only plausible organic result links become hits — the search
 * engine's own navigation (Videos/Shopping/Maps tabs, verticals, settings) never enters the route list,
 * while its redirect wrappers (the actual organic links on Bing & co.) and direct external links do.
 */
public class WebSearchProviderTest {

    private static final WebSearchProvider.OrganicResultSearchProvider PROVIDER =
            new WebSearchProvider.OrganicResultSearchProvider(
                    new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver());

    private static String bingWrapped(String target) {
        return "https://www.bing.com/ck/a?!&&p=abc&u=a1"
                + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(target.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static BrowserPageSnapshot bingPage() {
        return new BrowserPageSnapshot("https://www.bing.com/search?q=pf4j", "pf4j - Suchen", "…", false);
    }

    private static BrowserLink link(String text, String url) {
        return new BrowserLink("link-x", text, url);
    }

    @Test
    public void dropsProviderNavigationResolvesWrappersAndKeepsExternalLinks() {
        String wrapped = bingWrapped("https://pf4j.org/doc/getting-started.html");
        List<BrowserLink> links = new ArrayList<BrowserLink>();
        links.add(link("Videos", "https://www.bing.com/videos/search?q=pf4j"));
        links.add(link("Shopping", "https://www.bing.com/shopping?q=pf4j"));
        links.add(link("Bilder", "https://cn.bing.com/images/search?q=pf4j"));
        links.add(link("Einstellungen", "https://www.bing.com/account/general"));
        links.add(link("PF4J – Plugin Framework for Java", wrapped));
        links.add(link("Kaputter Wrapper", "https://www.bing.com/ck/a?!&&p=abc")); // no decodable target
        links.add(link("pf4j/pf4j: Plugin Framework", "https://github.com/pf4j/pf4j"));
        links.add(link("pf4j/pf4j: Plugin Framework", "https://github.com/pf4j/pf4j")); // duplicate
        links.add(link("", "https://no-text.example/"));
        links.add(link("Impressum", "javascript:void(0)"));

        List<WebSearchItem> items = PROVIDER.extract(bingPage(), links);

        assertEquals("resolved wrapper + external, deduped — nav and broken wrapper dropped",
                2, items.size());
        assertEquals("the NAVIGATION target stays the wrapper (the engine expects it followed)",
                wrapped, items.get(0).getUrl());
        assertEquals("https://github.com/pf4j/pf4j", items.get(1).getUrl());
        for (WebSearchItem item : items) {
            assertTrue(!item.getUrl().contains("/videos/") && !item.getUrl().contains("/shopping"));
        }
    }

    @Test
    public void returnsEmptyWhenNoOrganicRouteExists() {
        // Everything provider-internal (consent wall, JS-only results): "no routes from this engine" —
        // the SESSION then tries its fallback engines before degrading to the all-links extraction.
        List<BrowserLink> links = new ArrayList<BrowserLink>();
        links.add(link("Result A", "https://search.example/r/1"));
        links.add(link("Result B", "https://search.example/r/2"));
        BrowserPageSnapshot page =
                new BrowserPageSnapshot("https://search.example/find?q=x", "find", "…", false);

        assertEquals(0, PROVIDER.extract(page, links).size());
    }

    @Test
    public void capsTheRouteListForSmallModels() {
        List<BrowserLink> links = new ArrayList<BrowserLink>();
        for (int i = 1; i <= 40; i++) {
            links.add(link("Result " + i, "https://site" + i + ".example/a"));
        }
        List<WebSearchItem> items = PROVIDER.extract(bingPage(), links);
        assertEquals(WebSearchProvider.OrganicResultSearchProvider.MAX_RESULTS, items.size());
    }

}
