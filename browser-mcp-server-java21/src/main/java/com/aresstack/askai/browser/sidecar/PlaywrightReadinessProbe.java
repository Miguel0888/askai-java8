package com.aresstack.askai.browser.sidecar;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * {@link ReadinessProbe} over a live Playwright {@link Page}. Both queries are cheap and non-blocking: the
 * body length is read as a single number via {@code evaluate}, and selector presence via {@code querySelector}
 * with no implicit waiting. Must be used on the owning Playwright thread. A PlaywrightException here (e.g. the
 * execution context destroyed mid-navigation) propagates to the caller, which treats it as "not ready yet".
 */
final class PlaywrightReadinessProbe implements ReadinessProbe {

    private final Page page;

    PlaywrightReadinessProbe(Page page) {
        this.page = page;
    }

    @Override
    public long bodyTextLength() {
        Object raw = page.evaluate(
                "() => (document.body && document.body.innerText ? document.body.innerText.length : 0)");
        return raw instanceof Number ? ((Number) raw).longValue() : 0L;
    }

    @Override
    public boolean anySelectorPresent(List<String> cssSelectors) {
        for (String selector : cssSelectors) {
            if (page.querySelector(selector) != null) {
                return true;
            }
        }
        return false;
    }
}
