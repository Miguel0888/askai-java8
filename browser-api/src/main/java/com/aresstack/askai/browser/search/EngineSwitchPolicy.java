package com.aresstack.askai.browser.search;

/**
 * When the search moves on to the next engine. The SEMANTICS of the switch are a hard invariant
 * (NO_ORGANIC / EXTRACTION / NAVIGATION_FAILED → next engine; CHALLENGE_PENDING → park + next engine;
 * nothing reachable → technical failure) — this enum only names the ordering strategy.
 */
public enum EngineSwitchPolicy {
    /** Try engines strictly in configured order, advancing on every non-organic outcome. */
    SEQUENTIAL
}
