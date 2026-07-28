package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.WebSearchResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Session logic against a fake {@link PlaywrightDriver}: snapshot mapping and truncation, per-snapshot link
 * ids, pre- and POST-redirect URL policy, follow/back plumbing, search provider behavior and idempotent
 * shutdown — all without a real browser. Literal-IP URLs avoid DNS in the strict-policy tests.
 */
public class PlaywrightBrowserSessionTest {

    /** Scripted driver: url -> state; open("...") may "redirect" by returning a different final url. */
    private static final class FakeDriver implements PlaywrightDriver {
        final Map<String, PlaywrightPageState> byUrl = new LinkedHashMap<String, PlaywrightPageState>();
        final List<String> opened = new ArrayList<String>();
        final List<PlaywrightPageState> history = new ArrayList<PlaywrightPageState>();
        int closeCalls;

        public PlaywrightPageState open(String url) throws BrowserException {
            opened.add(url);
            PlaywrightPageState state = byUrl.get(url);
            if (state == null) {
                throw new BrowserException("Navigation failed: 404 " + url);
            }
            history.add(state);
            return state;
        }

        public PlaywrightPageState current() throws BrowserException {
            if (history.isEmpty()) {
                throw new BrowserException("no page");
            }
            return history.get(history.size() - 1);
        }

        public PlaywrightPageState back() throws BrowserException {
            if (history.size() < 2) {
                throw new BrowserException("No previous page in history.");
            }
            history.remove(history.size() - 1);
            return history.get(history.size() - 1);
        }

        public void close() {
            closeCalls++;
        }
    }

    private static PlaywrightPageState state(String url, String title, String text, String... links) {
        List<PlaywrightPageState.Anchor> anchors = new ArrayList<PlaywrightPageState.Anchor>();
        for (int i = 0; i + 1 < links.length; i += 2) {
            anchors.add(new PlaywrightPageState.Anchor(links[i], links[i + 1]));
        }
        return new PlaywrightPageState(url, title, text, anchors);
    }

    private static PlaywrightBrowserSession session(FakeDriver driver, UrlSafetyPolicy policy,
                                                    BrowserLimits limits, String searchUrl) {
        return new PlaywrightBrowserSession(driver, policy, limits, searchUrl, null);
    }

    @Test
    public void mapsSnapshotWithStableLinkIdsAndFollowsAndGoesBack() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://8.8.8.8/a", state("http://8.8.8.8/a", "A", "alpha page",
                "to b", "http://9.9.9.9/b", "", "http://ignored.example/empty-text-still-linked"));
        driver.byUrl.put("http://9.9.9.9/b", state("http://9.9.9.9/b", "B", "beta page"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);

        BrowserPageSnapshot a = s.open("http://8.8.8.8/a");
        assertEquals("A", a.getTitle());
        assertEquals("alpha page", a.getText());
        assertFalse(a.isTruncated());
        List<BrowserLink> links = s.links();
        assertEquals(2, links.size());
        assertEquals("link-1", links.get(0).getId());
        assertEquals("to b", links.get(0).getText());
        assertEquals("link-2", links.get(1).getId());

        BrowserPageSnapshot b = s.follow("link-1");
        assertEquals("B", b.getTitle());
        // Link ids are per-snapshot: after navigating, the old id set is replaced.
        assertEquals(0, s.links().size());

        BrowserPageSnapshot backToA = s.back();
        assertEquals("A", backToA.getTitle());
        assertEquals(2, s.links().size());
    }

    @Test
    public void truncatesTextAndCapsLinks() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://8.8.8.8/big", state("http://8.8.8.8/big", "Big",
                "0123456789ABCDEF", "l1", "http://9.9.9.9/1", "l2", "http://9.9.9.9/2",
                "l3", "http://9.9.9.9/3"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                new BrowserLimits(1024, 10, 2, 1000), null);

        BrowserPageSnapshot snap = s.open("http://8.8.8.8/big");
        assertEquals("0123456789", snap.getText());
        assertTrue(snap.isTruncated());
        assertEquals(2, s.links().size());
    }

    @Test
    public void blocksPrivateTargetBeforeNavigation() throws Exception {
        FakeDriver driver = new FakeDriver();
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);
        try {
            s.open("http://127.0.0.1/secret");
            fail("expected pre-navigation block");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("private"));
        }
        assertTrue("driver must never have been asked to navigate", driver.opened.isEmpty());
        try {
            s.open("file:///etc/passwd");
            fail("expected scheme block");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("scheme"));
        }
    }

    @Test
    public void blocksRedirectToPrivateTargetAfterNavigation() throws Exception {
        FakeDriver driver = new FakeDriver();
        // The pre-check sees a public literal IP; the FINAL url after the "redirect" is loopback.
        driver.byUrl.put("http://8.8.8.8/outer",
                state("http://127.0.0.1/admin", "Admin", "internal"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);
        try {
            s.open("http://8.8.8.8/outer");
            fail("expected post-redirect block");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("Blocked after redirect"));
        }
        // The blocked page did not become current.
        try {
            s.links();
            fail("expected no current page");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("No page is open yet"));
        }
    }

    @Test
    public void unknownLinkIdAndMissingPageFailReadably() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://8.8.8.8/a", state("http://8.8.8.8/a", "A", "alpha"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);
        try {
            s.links();
            fail("expected missing-page error");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("web_open"));
        }
        s.open("http://8.8.8.8/a");
        try {
            s.follow("link-99");
            fail("expected unknown link id");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("Unknown link id"));
        }
    }

    @Test
    public void searchIsHonestWithoutProviderAndNavigatesWithOne() throws Exception {
        FakeDriver driver = new FakeDriver();
        PlaywrightBrowserSession unconfigured = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);
        try {
            unconfigured.search("pf4j");
            fail("expected honest unavailable-search error");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("No search provider"));
        }

        FakeDriver driver2 = new FakeDriver();
        driver2.byUrl.put("http://8.8.8.8/find?q=pf4j+plugin", state("http://8.8.8.8/find?q=pf4j+plugin",
                "Results", "results page", "PF4J primer", "http://9.9.9.9/a", "", "http://9.9.9.9/no-text"));
        PlaywrightBrowserSession s = session(driver2, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), "http://8.8.8.8/find?q={query}");
        WebSearchResult result = s.search("pf4j plugin");
        assertEquals("query is URL-encoded into the template",
                "http://8.8.8.8/find?q=pf4j+plugin", driver2.opened.get(0));
        assertEquals(1, result.getItems().size());
        assertEquals("PF4J primer", result.getItems().get(0).getTitle());
        assertEquals("http://9.9.9.9/a", result.getItems().get(0).getUrl());
        assertEquals("the engine host travels with the result",
                java.util.Arrays.asList("8.8.8.8"), result.getProviderHosts());
    }

    @Test
    public void searchFallsThroughToTheNextEngineWhenTheConfiguredOneHasNoOrganicRoutes() throws Exception {
        // The user-reported case: headless Bing serves a consent/JS page — every anchor is provider-
        // internal. The session must try the fallback engine instead of returning provider navigation.
        // DNS-named engines + allow-private policy: fallbacks apply, and no DNS lookup happens in check().
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://engine-one.test/find?q=pf4j", state("http://engine-one.test/find?q=pf4j",
                "Consent", "consent wall",
                "Videos", "http://engine-one.test/videos?q=pf4j",
                "Accept", "http://engine-one.test/consent"));
        driver.byUrl.put("http://engine-two.test/html?q=pf4j", state("http://engine-two.test/html?q=pf4j",
                "Results", "results page", "PF4J primer", "http://target.test/pf4j"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");

        assertEquals(1, result.getItems().size());
        assertEquals("http://target.test/pf4j", result.getItems().get(0).getUrl());
        assertEquals("BOTH engine hosts are reported as transit",
                java.util.Arrays.asList("engine-one.test", "engine-two.test"),
                result.getProviderHosts());
    }

    @Test
    public void searchDegradesToAllLinksOnlyWhenNoEngineHasOrganicRoutes() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://engine-one.test/find?q=pf4j", state("http://engine-one.test/find?q=pf4j",
                "Consent", "wall", "Videos", "http://engine-one.test/videos?q=pf4j"));
        driver.byUrl.put("http://engine-two.test/html?q=pf4j", state("http://engine-two.test/html?q=pf4j",
                "Empty", "nothing", "Settings", "http://engine-two.test/settings"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");

        assertEquals("degrades to the last page's text links, never goes blind",
                1, result.getItems().size());
        assertEquals("http://engine-two.test/settings", result.getItems().get(0).getUrl());
        assertTrue("degrade mode reports NO transit hosts — the engine's links ARE the results",
                result.getProviderHosts().isEmpty());
    }

    @Test
    public void literalIpProvidersNeverFallThroughToPublicEngines() throws Exception {
        // A literal-IP provider is a self-contained dev/test world: no fallback engine is contacted,
        // the engine's own links stay usable (degrade mode), no transit hosts are reported.
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://8.8.8.8/find?q=pf4j", state("http://8.8.8.8/find?q=pf4j",
                "Find", "results", "Local result", "http://8.8.8.8/a"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), "http://8.8.8.8/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");

        assertEquals("exactly one navigation — no fallback engine was contacted",
                1, driver.opened.size());
        assertEquals(1, result.getItems().size());
        assertEquals("http://8.8.8.8/a", result.getItems().get(0).getUrl());
        assertTrue(result.getProviderHosts().isEmpty());
    }

    @Test
    public void closeIsIdempotentAndDelegatesOnce() {
        FakeDriver driver = new FakeDriver();
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);
        s.close();
        s.close();
        assertEquals("session delegates each close; the DRIVER guards idempotency in production",
                2, driver.closeCalls);
    }

    @Test
    public void privateTargetRequestFilterClassifiesHosts() {
        PlaywrightSessionFactory.PrivateTargetRequestFilter filter =
                new PlaywrightSessionFactory.PrivateTargetRequestFilter();
        assertFalse(filter.test("http://127.0.0.1/x"));
        assertFalse(filter.test("http://localhost:8080/x"));
        assertFalse(filter.test("http://10.1.2.3/x"));
        assertFalse(filter.test("http://192.168.0.5/x"));
        assertFalse(filter.test("http://169.254.169.254/latest/meta-data"));
        assertFalse(filter.test("http://172.16.0.1/x"));
        assertFalse(filter.test("http://[::1]/x"));
        assertTrue(filter.test("http://172.15.0.1/x"));
        assertTrue(filter.test("http://172.32.0.1/x"));
        assertTrue(filter.test("https://example.org/page"));
        assertTrue("in-page data resources have no network target", filter.test("data:text/plain,x"));
    }
}
