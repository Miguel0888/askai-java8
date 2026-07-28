package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
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
    private final WebSearchProvider searchProvider;
    private List<BrowserLink> currentLinks = Collections.emptyList();
    private boolean hasPage;

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider) {
        this.driver = driver;
        this.policy = policy;
        this.limits = limits;
        this.searchUrlTemplate = searchUrlTemplate == null || searchUrlTemplate.trim().isEmpty()
                ? null : searchUrlTemplate.trim();
        this.searchProvider = searchProvider == null
                ? new WebSearchProvider.OrganicResultSearchProvider() : searchProvider;
    }

    public BrowserBackendKind getBackendKind() {
        return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
    }

    /**
     * Scrape-friendly, server-rendered fallback engines: when the CONFIGURED engine yields no organic
     * routes (consent wall, JS-only result page, blocking), the search falls through to these before
     * degrading to the legacy all-links extraction. Their result links are direct or {@code /l/}-wrapped,
     * which the street-sign extraction understands.
     */
    static final String[] FALLBACK_SEARCH_TEMPLATES = {
            "https://html.duckduckgo.com/html/?q={query}",
            "https://lite.duckduckgo.com/lite/?q={query}"
    };

    private String[] fallbackSearchTemplates = FALLBACK_SEARCH_TEMPLATES;

    /** Test seam: replace the built-in fallback engines (tests use literal-IP URLs, no DNS). */
    void setFallbackSearchTemplates(String[] templates) {
        this.fallbackSearchTemplates = templates == null ? new String[0] : templates;
    }

    public WebSearchResult search(String query) throws BrowserException {
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
        if (isDnsName(WebSearchProvider.OrganicResultSearchProvider.hostOf(searchUrlTemplate))) {
            for (String fallback : fallbackSearchTemplates) {
                if (!fallback.equals(searchUrlTemplate)) {
                    templates.add(fallback);
                }
            }
        }
        List<String> providerHosts = new ArrayList<String>();
        BrowserPageSnapshot lastPage = null;
        List<BrowserLink> lastLinks = null;
        BrowserException lastFailure = null;
        for (String template : templates) {
            BrowserPageSnapshot page;
            try {
                page = open(template.replace("{query}", encoded));
            } catch (BrowserException engineUnreachable) {
                lastFailure = engineUnreachable;
                continue; // this engine is down/blocked — the next one may still deliver routes
            }
            String host = WebSearchProvider.OrganicResultSearchProvider.hostOf(page.getUrl());
            if (!host.isEmpty() && !providerHosts.contains(host)) {
                providerHosts.add(host);
            }
            List<com.aresstack.askai.browser.WebSearchItem> organic =
                    searchProvider.extract(page, currentLinks);
            if (!organic.isEmpty()) {
                return new WebSearchResult(organic, providerHosts);
            }
            lastPage = page;
            lastLinks = currentLinks;
        }
        if (lastPage == null) {
            throw lastFailure != null ? lastFailure
                    : new BrowserException("Search failed: no engine was reachable.");
        }
        // No engine produced organic routes: degrade to the legacy all-links extraction, never go blind.
        // Deliberately WITHOUT provider hosts: in this mode the engine's own links ARE the results
        // (single-host dev/test worlds) — marking the host as transit would make them unusable.
        return new WebSearchResult(
                new WebSearchProvider.LinkListSearchProvider().extract(lastPage, lastLinks),
                Collections.<String>emptyList());
    }

    public BrowserPageSnapshot open(String url) throws BrowserException {
        policy.check(url);
        return checkedSnapshot(driver.open(url));
    }

    public BrowserPageSnapshot currentPage() throws BrowserException {
        requirePage();
        return checkedSnapshot(driver.current());
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

    /** True for registered DNS names ("www.bing.com"); false for literal IPs/ports-only authorities. */
    private static boolean isDnsName(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = Character.toLowerCase(host.charAt(i));
            if (c >= 'a' && c <= 'z') {
                return true;
            }
        }
        return false;
    }

    private static String encode(String value) throws BrowserException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new BrowserException("Cannot encode query.");
        }
    }
}
