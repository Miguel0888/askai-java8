package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.WebSearchResult;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The productive Playwright-backed {@link BrowserSession}. The URL policy is applied TWICE per navigation:
 * before navigating and again on the FINAL URL after redirects — a public URL that redirects to a private or
 * loopback target fails the call and the page is abandoned. Snapshots stay small and deterministic (text
 * capped at {@link BrowserLimits#getMaxTextChars()}, links at {@link BrowserLimits#getMaxLinks()} with
 * per-snapshot stable ids {@code link-1..n}); old link ids are invalidated by the next navigation.
 * {@code web_search} navigates to the configured provider URL — without one it fails honestly.
 */
final class PlaywrightBrowserSession implements BrowserSession {

    private final PlaywrightDriver driver;
    private final UrlSafetyPolicy policy;
    private final BrowserLimits limits;
    private final String searchUrlTemplate;
    private WebSearchProvider searchProvider;
    private List<BrowserLink> currentLinks = Collections.emptyList();
    private boolean hasPage;
    /** MANUAL_CHALLENGE_PENDING: the domain family whose parked challenge waits for the user, or null. */
    private String challengeFamily;
    private String challengeUrl;
    /** Monotonic per navigation — the stale-reference guard of every rendered-page snapshot. */
    private long snapshotGeneration;

    /** The typed configuration contract; defaults come ONLY from LegacyBrowserSearchDefaults. */
    private final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings;

    /** Public-suffix aware domain families; tests may inject a fake (e.g. host:port for local worlds). */
    private com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys =
            new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver();

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider) {
        this(driver, policy, limits, searchUrlTemplate, searchProvider,
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
    }

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider,
                             com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings) {
        this.driver = driver;
        this.policy = policy;
        this.limits = limits;
        this.settings = settings;
        this.searchUrlTemplate = searchUrlTemplate == null || searchUrlTemplate.trim().isEmpty()
                ? null : searchUrlTemplate.trim();
        this.fallbackSearchTemplates = settings.navigation.fallbackEngineTemplates
                .toArray(new String[0]);
        this.searchProvider = searchProvider == null
                ? new WebSearchProvider.OrganicResultSearchProvider(settings) : searchProvider;
    }

    /**
     * Inject a different {@link com.aresstack.askai.browser.domain.DomainKeyResolver} (tests/dev modes,
     * e.g. host:port keys for local multi-server worlds). The default organic extraction is rebuilt on
     * the same domain semantics; a custom search provider stays untouched.
     */
    void setDomainKeyResolver(com.aresstack.askai.browser.domain.DomainKeyResolver resolver) {
        if (resolver != null) {
            this.domainKeys = resolver; // capture + engine policy pick it up on the next search
        }
    }

    public BrowserBackendKind getBackendKind() {
        return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
    }

    /**
     * Scrape-friendly, server-rendered fallback engines (from the navigation settings; defaults in
     * LegacyBrowserSearchDefaults): when the CONFIGURED engine yields no organic routes (consent wall,
     * JS-only result page, blocking), the search falls through to these. If NO engine has organic
     * routes, the result is typed and EMPTY — never the SERP's raw anchors (hard invariant of the
     * Legacy-Browser-Search requirements).
     */
    private String[] fallbackSearchTemplates;

    /** Test seam: replace the built-in fallback engines (tests use literal-IP URLs, no DNS). */
    void setFallbackSearchTemplates(String[] templates) {
        this.fallbackSearchTemplates = templates == null ? new String[0] : templates;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public WebSearchResult search(String query) throws BrowserException {
        // web_search: run the SHARED engine navigation; the consumer extracts each captured page and
        // stops on the first organic hit — exactly the previous behaviour, no second navigation.
        final List<com.aresstack.askai.browser.WebSearchItem>[] organic = new List[]{null};
        EngineNavigation nav = navigateAndCaptureSearchEngines(query, new CapturedPageConsumer() {
            public boolean accept(com.aresstack.askai.browser.render.RenderedPageDocument document,
                    String host,
                    List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts) {
                com.aresstack.askai.browser.search.SearchResultExtractionResult extraction =
                        searchProvider.extract(document);
                switch (extraction.outcome) {
                    case ORGANIC_RESULTS:
                        List<com.aresstack.askai.browser.WebSearchItem> items =
                                new ArrayList<com.aresstack.askai.browser.WebSearchItem>();
                        for (com.aresstack.askai.browser.search.SearchResultCandidate candidate
                                : extraction.candidates) {
                            if (items.size() >= settings.navigation.searchResultLimit) {
                                break;
                            }
                            items.add(new com.aresstack.askai.browser.WebSearchItem(
                                    String.valueOf(items.size() + 1), candidate.title,
                                    candidate.resolvedTargetUrl, candidate.snippet));
                        }
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.ORGANIC_RESULTS,
                                items.size() + " candidates"));
                        organic[0] = items;
                        return true;
                    case NO_ORGANIC_RESULTS:
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.NO_ORGANIC_RESULTS,
                                bounded(firstDiagnostic(extraction))));
                        return false;
                    default:
                        // An ununderstood layout is an extraction FAILURE, never an empty engine.
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                                bounded(firstDiagnostic(extraction))));
                        return false;
                }
            }
        });
        if (organic[0] != null) {
            return new WebSearchResult(organic[0], nav.providerHosts, nav.attempts);
        }
        if (!nav.anyEngineReached && challengeFamily == null && nav.lastFailure != null) {
            throw nav.lastFailure; // nothing was reachable at all — a plain technical failure
        }
        // HARD INVARIANT: no path ever returns the SERP's raw anchors as results.
        return new WebSearchResult(
                Collections.<com.aresstack.askai.browser.WebSearchItem>emptyList(),
                nav.providerHosts, nav.attempts);
    }

    /** The captured-page hook for the shared engine navigation — returns true to STOP the loop. */
    interface CapturedPageConsumer {
        boolean accept(com.aresstack.askai.browser.render.RenderedPageDocument document, String host,
                       List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts);
    }

    /** The shared outcome of one engine-navigation pass. */
    static final class EngineNavigation {
        final List<String> providerHosts;
        final List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts;
        final List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges;
        final boolean anyEngineReached;
        final BrowserException lastFailure;

        EngineNavigation(List<String> providerHosts,
                List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts,
                List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges,
                boolean anyEngineReached, BrowserException lastFailure) {
            this.providerHosts = providerHosts;
            this.attempts = attempts;
            this.challenges = challenges;
            this.anyEngineReached = anyEngineReached;
            this.lastFailure = lastFailure;
        }
    }

    /**
     * The SINGLE engine-navigation loop (templates, fallback engines, domain-family locks, consent,
     * challenge detection, provider hosts, capture, per-engine attempts). web_search and
     * web_search_prepare both drive it; the consumer decides what to do with each captured page and
     * when to stop, so web_search behaviour is unchanged and no ticket is left behind.
     */
    EngineNavigation navigateAndCaptureSearchEngines(String query, CapturedPageConsumer consumer)
            throws BrowserException {
        if (searchUrlTemplate == null) {
            throw new BrowserException("No search provider is configured for the Playwright backend "
                    + "(start the sidecar with --search-url=<template containing {query}>).");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new BrowserException("Empty search query.");
        }
        String encoded = encode(query.trim());
        List<String> templates = new ArrayList<String>();
        templates.add(searchUrlTemplate);
        // Fallback engines only make sense behind a PUBLIC engine (Bing & co.). A literal-IP/localhost
        // provider is a self-contained dev/test world — falling through to a public engine would leave it.
        if (domainKeys.resolve(searchUrlTemplate).getHostKind()
                == com.aresstack.askai.browser.domain.HostKind.REGISTERED_NAME) {
            for (String fallback : fallbackSearchTemplates) {
                if (!fallback.equals(searchUrlTemplate)
                        && templates.size() < settings.navigation.maximumEngineAttempts) {
                    templates.add(fallback);
                }
            }
        }
        List<String> providerHosts = new ArrayList<String>();
        List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts =
                new ArrayList<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>();
        List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges =
                new ArrayList<com.aresstack.askai.browser.search.repair.SearchChallengeState>();
        boolean anyEngineReached = false;
        BrowserException lastFailure = null;
        for (String template : templates) {
            String engineHost = com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver
                    .hostOf(template);
            String engineFamily = domainKeys.resolve(template).getRegistrableDomain();
            if (engineFamily.equals(challengeFamily)) {
                // MANUAL_CHALLENGE_PENDING: no new search, no reload, no retry on this family.
                attempts.add(attempt(engineHost,
                        com.aresstack.askai.browser.LegacySearchAttemptOutcome.CHALLENGE_PENDING,
                        "family locked"));
                continue;
            }
            BrowserPageSnapshot page;
            try {
                page = open(template.replace("{query}", encoded));
            } catch (BrowserException engineUnreachable) {
                lastFailure = engineUnreachable;
                attempts.add(attempt(engineHost,
                        com.aresstack.askai.browser.LegacySearchAttemptOutcome.NAVIGATION_FAILED,
                        engineUnreachable.getMessage()));
                continue; // this engine is down/blocked — the next one may still deliver routes
            }
            anyEngineReached = true;
            // SERP guards, encapsulated here (never in the research loop): consent first, then the
            // challenge check — a challenge page is never read as a result page.
            if (driver.tryDismissConsent().startsWith("clicked")) {
                page = checkedSnapshot(driver.current()); // re-read without the consent overlay
            }
            com.aresstack.askai.browser.domain.DomainIdentity pageIdentity =
                    domainKeys.resolve(page.getUrl());
            String host = pageIdentity.getHost();
            if (driver.challengePresent()) {
                if (challengeFamily == null && driver.parkChallenge()) {
                    challengeFamily = pageIdentity.getRegistrableDomain();
                    challengeUrl = page.getUrl();
                }
                challenges.add(new com.aresstack.askai.browser.search.repair.SearchChallengeState(
                        pageIdentity.getRegistrableDomain(), page.getUrl()));
                attempts.add(attempt(host,
                        com.aresstack.askai.browser.LegacySearchAttemptOutcome.CHALLENGE_PENDING,
                        "manual challenge"));
                continue; // the user solves it manually; try the next engine meanwhile
            }
            // Transit semantics only exist for PUBLIC engines; an IP/localhost dev world has no
            // engine navigation to hide, so it never marks itself as transit.
            if (!host.isEmpty() && !providerHosts.contains(host)
                    && pageIdentity.getHostKind()
                            == com.aresstack.askai.browser.domain.HostKind.REGISTERED_NAME) {
                providerHosts.add(host);
            }
            // A3: judge the STRUCTURED rendered page — container hierarchy, repeated result
            // blocks, primary links, snippets. Navigation targets are the RESOLVED direct URLs.
            com.aresstack.askai.browser.render.RenderedPageDocument document;
            try {
                document = driver.captureRenderedPage(domainKeys, ++snapshotGeneration);
            } catch (BrowserException captureFailed) {
                attempts.add(attempt(host,
                        com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                        bounded(captureFailed.getMessage())));
                continue;
            }
            if (document == null) {
                attempts.add(attempt(host,
                        com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                        "structured page capture unavailable"));
                continue;
            }
            // The consumer decides what to do with this captured page and whether to stop the loop
            // (web_search stops on the first organic hit; web_search_prepare keeps collecting).
            if (consumer.accept(document, host, attempts)) {
                break;
            }
        }
        return new EngineNavigation(providerHosts, attempts, challenges, anyEngineReached,
                lastFailure);
    }

    private static String firstDiagnostic(
            com.aresstack.askai.browser.search.SearchResultExtractionResult extraction) {
        return extraction.diagnostics.isEmpty() ? "" : extraction.diagnostics.get(
                extraction.diagnostics.size() - 1);
    }

    /** Attempt diagnostics are bounded by the diagnostics settings — never unbounded dumps. */
    private String bounded(String text) {
        String value = text == null ? "" : text;
        int limit = settings.diagnostics.maximumTextExcerptCharacters;
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    private static com.aresstack.askai.browser.LegacySearchEngineAttemptResult attempt(
            String engineHost, com.aresstack.askai.browser.LegacySearchAttemptOutcome outcome,
            String diagnostic) {
        return new com.aresstack.askai.browser.LegacySearchEngineAttemptResult(engineHost, outcome,
                diagnostic == null ? "" : diagnostic);
    }

    public BrowserPageSnapshot open(String url) throws BrowserException {
        policy.check(url);
        return checkedSnapshot(driver.open(url));
    }

    public BrowserPageSnapshot currentPage() throws BrowserException {
        requirePage();
        return checkedSnapshot(driver.current());
    }

    @Override
    public BrowserPageReadiness probe(String url) throws BrowserException {
        policy.check(url);
        return readinessFrom(checkedSnapshot(driver.open(url)));
    }

    @Override
    public BrowserPageReadiness probeCurrent() throws BrowserException {
        requirePage();
        return readinessFrom(checkedSnapshot(driver.current()));
    }

    @Override
    public String dismissConsent() throws BrowserException {
        requirePage();
        return driver.tryDismissConsent();
    }

    @Override
    public String renderHud(String stateLine) throws BrowserException {
        requirePage();
        return driver.renderHud(stateLine);
    }

    @Override
    public String pollHudCommands() throws BrowserException {
        requirePage();
        return driver.pollHudCommands();
    }

    /** Compose a readability probe from a snapshot plus the driver's consent/challenge guards. */
    private BrowserPageReadiness readinessFrom(BrowserPageSnapshot s) {
        String marker = driver.challengeMarker(); // 'visible:…' | 'hidden:…' | 'none'
        boolean present = marker != null && !marker.isEmpty() && !"none".equals(marker);
        boolean visible = marker != null && marker.startsWith("visible");
        String consent = driver.consentCandidate();
        boolean consentPresent = consent != null && consent.startsWith("candidate");
        int len = s.getText() == null ? 0 : s.getText().length();
        return new BrowserPageReadiness(s.getUrl(), s.getTitle(), len,
                BrowserPageReadiness.excerptOf(s.getText()),
                present, visible, present ? marker : "",
                consentPresent, consentPresent ? consent : "");
    }

    public List<BrowserLink> links() throws BrowserException {
        requirePage();
        return currentLinks;
    }

    public BrowserPageSnapshot follow(String linkId) throws BrowserException {
        requirePage();
        for (BrowserLink link : currentLinks) {
            if (link.getId().equals(linkId)) {
                return open(link.getUrl());
            }
        }
        throw new BrowserException("Unknown link id: " + linkId + " (link ids are only valid for the "
                + "current page; call web_links first).");
    }

    public BrowserPageSnapshot back() throws BrowserException {
        requirePage();
        return checkedSnapshot(driver.back());
    }

    /**
     * Poll the parked manual challenge WITHOUT reloading or focusing it: while present → CHALLENGE line;
     * on first disappearance → RESOLVED line (challenge tab closed, family unlocked); otherwise NONE.
     */
    public List<String> challengeStatus() {
        if (challengeFamily == null) {
            return Collections.singletonList("NONE");
        }
        if (driver.parkedChallengeStillPresent()) {
            return Collections.singletonList("CHALLENGE: " + challengeFamily + " " + challengeUrl);
        }
        String resolved = challengeFamily;
        driver.closeParkedChallenge();
        challengeFamily = null;
        challengeUrl = null;
        return Collections.singletonList("RESOLVED: " + resolved);
    }

    public void close() {
        driver.close();
    }

    /** The post-redirect gate: the FINAL url must pass the policy or the page is abandoned. */
    private BrowserPageSnapshot checkedSnapshot(PlaywrightPageState state) throws BrowserException {
        try {
            policy.check(state.url);
        } catch (BrowserException blocked) {
            abandonPage();
            throw new BrowserException("Blocked after redirect — final URL is not allowed: "
                    + blocked.getMessage());
        }
        String text = state.text;
        boolean truncated = false;
        if (text.length() > limits.getMaxTextChars()) {
            text = text.substring(0, limits.getMaxTextChars());
            truncated = true;
        }
        List<BrowserLink> links = new ArrayList<BrowserLink>();
        int id = 0;
        for (PlaywrightPageState.Anchor anchor : state.anchors) {
            if (anchor.href.isEmpty()) {
                continue;
            }
            if (links.size() >= limits.getMaxLinks()) {
                truncated = true;
                break;
            }
            id++;
            links.add(new BrowserLink("link-" + id, anchor.text, anchor.href));
        }
        currentLinks = Collections.unmodifiableList(links);
        hasPage = true;
        return new BrowserPageSnapshot(state.url, state.title, text, truncated);
    }

    /** After a blocked redirect the offending page must not stay current. */
    private void abandonPage() {
        currentLinks = Collections.emptyList();
        hasPage = false;
        try {
            driver.back();
        } catch (BrowserException ignored) {
            // No history to fall back to — the session simply has no current page anymore.
        }
    }

    private void requirePage() throws BrowserException {
        if (!hasPage) {
            throw new BrowserException("No page is open yet — call web_open or web_search first.");
        }
    }

    private static String encode(String value) throws BrowserException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new BrowserException("Cannot encode query.");
        }
    }
}
