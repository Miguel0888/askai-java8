package com.aresstack.askai.browser.sidecar;

/**
 * The small mutable memory a stateful readiness inspection needs BETWEEN non-blocking probes (one instance
 * per tab / per blocking wait). Kept out of {@link PageReadinessStrategy} so strategies stay stateless and
 * reusable across tabs. Only the generic content-stability strategy uses it today (tracking the last body
 * length and how many equal polls in a row have been seen).
 */
final class ReadinessState {

    long previousLength = -1;
    int stablePolls;
}
