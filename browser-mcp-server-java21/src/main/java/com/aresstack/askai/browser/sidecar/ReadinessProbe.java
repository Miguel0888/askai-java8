package com.aresstack.askai.browser.sidecar;

import java.util.List;

/**
 * The cheap, NON-BLOCKING inputs a {@link PageReadinessStrategy} inspects — never sleeps, never navigates.
 * One probe wraps one page (real Playwright page in production, a scripted fake in scheduler tests), so
 * readiness logic is decided without a browser. A probe call may fail transiently while the page is
 * mid-navigation (execution context destroyed); strategies surface that as {@code null}/exception handling
 * upstream rather than blocking.
 */
interface ReadinessProbe {

    /** Length of the RENDERED body text (a number is cheap to transfer, the text itself is not). */
    long bodyTextLength();

    /** True if ANY of the given CSS selectors currently matches an element on the page. */
    boolean anySelectorPresent(List<String> cssSelectors);
}
