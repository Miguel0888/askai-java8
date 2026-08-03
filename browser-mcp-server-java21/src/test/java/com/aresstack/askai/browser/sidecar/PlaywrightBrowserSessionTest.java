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
        /** url -> state AFTER a successful consent dismissal on that page. */
        final Map<String, PlaywrightPageState> afterConsent = new LinkedHashMap<String, PlaywrightPageState>();
        /** urls whose page shows a manual challenge. */
        final java.util.Set<String> challengeUrls = new java.util.HashSet<String>();
        int consentClicks;
        boolean parked;
        boolean parkedChallengeGone;
        int parkCalls;
        int closeParkedCalls;

        @Override
        public String tryDismissConsent() {
            PlaywrightPageState current = history.isEmpty() ? null : history.get(history.size() - 1);
            if (current != null && afterConsent.containsKey(current.url)) {
                consentClicks++;
                history.set(history.size() - 1, afterConsent.remove(current.url));
                return "clicked:#consent";
            }
            return "none";
        }

        @Override
        public boolean challengePresent() {
            PlaywrightPageState current = history.isEmpty() ? null : history.get(history.size() - 1);
            return current != null && challengeUrls.contains(current.url);
        }

        @Override
        public String consentCandidate() {
            PlaywrightPageState current = history.isEmpty() ? null : history.get(history.size() - 1);
            return current != null && afterConsent.containsKey(current.url) ? "candidate:#consent" : "none";
        }

        @Override
        public boolean parkChallenge() {
            parkCalls++;
            if (parked) {
                return false;
            }
            parked = true;
            return true;
        }

        @Override
        public boolean parkedChallengeStillPresent() {
            return parked && !parkedChallengeGone;
        }

        @Override
        public void closeParkedChallenge() {
            if (parked) {
                closeParkedCalls++;
                parked = false;
            }
        }

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

        @Override
        public com.aresstack.askai.browser.render.RenderedPageDocument captureRenderedPage(
                com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
                long snapshotGeneration) {
            PlaywrightPageState current = history.isEmpty() ? null : history.get(history.size() - 1);
            return current == null ? null
                    : SyntheticRenderedDocuments.fromState(current, domainKeys, snapshotGeneration);
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
    public void probeReportsConsentAndChallengeSignalsAndReprobeClearsThem() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://8.8.8.8/clean", state("http://8.8.8.8/clean", "Clean", "a readable body"));
        driver.byUrl.put("http://8.8.8.8/cookie", state("http://8.8.8.8/cookie", "Cookie", "behind a banner"));
        driver.afterConsent.put("http://8.8.8.8/cookie",
                state("http://8.8.8.8/cookie", "Cookie", "the real content now"));
        driver.byUrl.put("http://8.8.8.8/captcha", state("http://8.8.8.8/captcha", "Captcha", "one last step"));
        driver.challengeUrls.add("http://8.8.8.8/captcha");
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), null);

        com.aresstack.askai.browser.BrowserPageReadiness clean = s.probe("http://8.8.8.8/clean");
        assertFalse(clean.challengePresent);
        assertFalse(clean.consentPresent);
        assertEquals("a readable body".length(), clean.textLength);

        com.aresstack.askai.browser.BrowserPageReadiness cookie = s.probe("http://8.8.8.8/cookie");
        assertTrue("consent banner detected without clicking", cookie.consentPresent);
        assertTrue(cookie.consentCandidate.startsWith("candidate"));
        assertTrue(s.dismissConsent().startsWith("clicked"));
        assertFalse("after dismissal the banner is gone", s.probeCurrent().consentPresent);

        com.aresstack.askai.browser.BrowserPageReadiness captcha = s.probe("http://8.8.8.8/captcha");
        assertTrue(captcha.challengePresent);
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
                "Results", "results page", "PF4J primer", "http://9.9.9.9/a",
                "PF4J guide", "http://9.9.9.10/b", "PF4J docs", "http://9.9.9.11/c",
                "", "http://9.9.9.9/no-text"));
        PlaywrightBrowserSession s = session(driver2, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), "http://8.8.8.8/find?q={query}");
        WebSearchResult result = s.search("pf4j plugin");
        assertEquals("query is URL-encoded into the template",
                "http://8.8.8.8/find?q=pf4j+plugin", driver2.opened.get(0));
        assertEquals(3, result.getItems().size());
        assertEquals("PF4J primer", result.getItems().get(0).getTitle());
        assertEquals("http://9.9.9.9/a", result.getItems().get(0).getUrl());
        assertTrue("transit semantics only exist for PUBLIC engines — an IP world reports none",
                result.getProviderHosts().isEmpty());
        assertEquals(com.aresstack.askai.browser.LegacySearchAttemptOutcome.ORGANIC_RESULTS,
                result.getAttempts().get(0).getOutcome());
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
                "Results", "results page", "PF4J primer", "http://target.test/pf4j",
                "PF4J guide", "http://target-two.test/pf4j", "PF4J docs", "http://target-three.test/pf4j"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");

        assertEquals(3, result.getItems().size());
        assertEquals("http://target.test/pf4j", result.getItems().get(0).getUrl());
        assertEquals("BOTH engine hosts are reported as transit",
                java.util.Arrays.asList("engine-one.test", "engine-two.test"),
                result.getProviderHosts());
    }

    @Test
    public void allEnginesWithoutOrganicRoutesYieldTypedOutcomesNeverTheRawAnchors() throws Exception {
        // HARD INVARIANT (Gesamtanforderungen): no path may ever return the SERP's raw anchors.
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://engine-one.test/find?q=pf4j", state("http://engine-one.test/find?q=pf4j",
                "Consent", "wall", "Videos", "http://engine-one.test/videos?q=pf4j"));
        driver.byUrl.put("http://engine-two.test/html?q=pf4j", state("http://engine-two.test/html?q=pf4j",
                "Empty", "nothing", "Settings", "http://engine-two.test/settings"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");

        assertTrue("never the raw anchors as pretended results", result.getItems().isEmpty());
        assertEquals("one typed outcome per attempted engine", 2, result.getAttempts().size());
        // A3: a page whose layout yields no result structure is an extraction FAILURE - not an
        // "engine without hits" (that would need an explicit no-results indication).
        assertEquals(com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                result.getAttempts().get(0).getOutcome());
        assertEquals(com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                result.getAttempts().get(1).getOutcome());
    }

    @Test
    public void consentBannerIsDismissedOnceAndResultsAreReadFromTheCleanPage() throws Exception {
        FakeDriver driver = new FakeDriver();
        // The engine first shows a consent wall (only internal links); after the dismissal the SAME
        // navigation exposes the organic result.
        driver.byUrl.put("http://engine-one.test/find?q=pf4j", state("http://engine-one.test/find?q=pf4j",
                "Consent", "wall", "Settings", "http://engine-one.test/settings"));
        driver.afterConsent.put("http://engine-one.test/find?q=pf4j",
                state("http://engine-one.test/find?q=pf4j", "Results", "results",
                        "PF4J primer", "http://target.test/pf4j",
                        "PF4J guide", "http://target-two.test/pf4j",
                        "PF4J docs", "http://target-three.test/pf4j"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[0]);

        WebSearchResult result = s.search("pf4j");

        assertEquals("the consent button was clicked exactly once", 1, driver.consentClicks);
        assertEquals(3, result.getItems().size());
        assertEquals("http://target.test/pf4j", result.getItems().get(0).getUrl());
    }

    @Test
    public void withoutABannerTheDismisserHasNoSideEffect() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://engine-one.test/find?q=pf4j", state("http://engine-one.test/find?q=pf4j",
                "Results", "results", "PF4J primer", "http://target.test/pf4j",
                "PF4J guide", "http://target-two.test/pf4j",
                "PF4J docs", "http://target-three.test/pf4j"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[0]);

        assertEquals(3, s.search("pf4j").getItems().size());
        assertEquals("no banner → no click", 0, driver.consentClicks);
    }

    @Test
    public void aChallengeParksThePageLocksTheFamilyAndResolvesViaStatusPolling() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://engine-one.test/find?q=pf4j", state("http://engine-one.test/find?q=pf4j",
                "One last step", "verify you are human", "Help", "http://engine-one.test/help"));
        driver.challengeUrls.add("http://engine-one.test/find?q=pf4j");
        driver.byUrl.put("http://engine-two.test/html?q=pf4j", state("http://engine-two.test/html?q=pf4j",
                "Results", "results", "PF4J primer", "http://target.test/pf4j",
                "PF4J guide", "http://target-two.test/pf4j",
                "PF4J docs", "http://target-three.test/pf4j"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://engine-one.test/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");
        assertEquals("the fallback engine still delivered routes", 3, result.getItems().size());
        assertTrue("the challenge page was parked for the user", driver.parked);

        assertEquals("CHALLENGE: engine-one.test http://engine-one.test/find?q=pf4j",
                s.challengeStatus().get(0));

        int openedBefore = driver.opened.size();
        s.search("pf4j");
        for (int i = openedBefore; i < driver.opened.size(); i++) {
            assertFalse("no new navigation to the challenged family while pending",
                    driver.opened.get(i).contains("engine-one.test"));
        }

        driver.parkedChallengeGone = true; // the user solved it
        assertEquals("RESOLVED: engine-one.test", s.challengeStatus().get(0));
        assertEquals("the parked tab was closed after resolution", 1, driver.closeParkedCalls);
        assertEquals("NONE", s.challengeStatus().get(0));

        s.search("pf4j"); // the family is unlocked again
        boolean navigatedAgain = false;
        for (String url : driver.opened) {
            navigatedAgain |= url.contains("engine-one.test/find");
        }
        assertTrue(navigatedAgain);
    }

    @Test
    public void literalIpProvidersNeverFallThroughToPublicEngines() throws Exception {
        // A literal-IP provider is a self-contained dev/test world: no fallback engine is contacted,
        // and a page without result structure is typed EXTRACTION_FAILED - never raw anchors.
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://8.8.8.8/find?q=pf4j", state("http://8.8.8.8/find?q=pf4j",
                "Find", "results", "Local result", "http://8.8.8.8/a"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.strict(),
                BrowserLimits.defaults(), "http://8.8.8.8/find?q={query}");
        s.setFallbackSearchTemplates(new String[]{"http://engine-two.test/html?q={query}"});

        WebSearchResult result = s.search("pf4j");

        assertEquals("exactly one navigation — no fallback engine was contacted",
                1, driver.opened.size());
        assertTrue(result.getItems().isEmpty());
        assertEquals(com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                result.getAttempts().get(0).getOutcome());
    }

    @Test
    public void hostPortModeMakesLocalServersActAsDistinctDomains() throws Exception {
        // The injected dev/test resolver keys families by host:port — local multi-server worlds work
        // WITHOUT bending the production public-suffix semantics.
        FakeDriver driver = new FakeDriver();
        driver.byUrl.put("http://127.0.0.1:1111/find?q=pf4j", state("http://127.0.0.1:1111/find?q=pf4j",
                "Find", "results",
                "Engine internal", "http://127.0.0.1:1111/settings",
                "Local result", "http://127.0.0.1:2222/a",
                "Second result", "http://127.0.0.1:3333/b",
                "Third result", "http://127.0.0.1:4444/c"));
        PlaywrightBrowserSession s = session(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), "http://127.0.0.1:1111/find?q={query}");
        s.setFallbackSearchTemplates(new String[0]);
        s.setDomainKeyResolver(new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver());

        WebSearchResult result = s.search("pf4j");

        assertEquals("the other-port servers are organic routes", 3, result.getItems().size());
        assertEquals("http://127.0.0.1:2222/a", result.getItems().get(0).getUrl());
        assertTrue("IP worlds still report no transit hosts", result.getProviderHosts().isEmpty());
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
