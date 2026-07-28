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

    public WebSearchResult search(String query) throws BrowserException {
        if (searchUrlTemplate == null) {
            throw new BrowserException("No search provider is configured for the Playwright backend "
                    + "(start the sidecar with --search-url=<template containing {query}>).");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new BrowserException("Empty search query.");
        }
        BrowserPageSnapshot page = open(searchUrlTemplate.replace("{query}", encode(query.trim())));
        return new WebSearchResult(searchProvider.extract(page, currentLinks),
                WebSearchProvider.OrganicResultSearchProvider.hostOf(page.getUrl()));
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

    private static String encode(String value) throws BrowserException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new BrowserException("Cannot encode query.");
        }
    }
}
