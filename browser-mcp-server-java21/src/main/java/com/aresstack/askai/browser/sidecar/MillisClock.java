package com.aresstack.askai.browser.sidecar;

/**
 * The single time source for the tab scheduler — injected so stagger, per-tab deadlines and await budgets are
 * deterministic under test (a fake clock advances by hand). Production uses {@link #system()}.
 */
interface MillisClock {

    long nowMillis();

    static MillisClock system() {
        return System::currentTimeMillis;
    }
}
