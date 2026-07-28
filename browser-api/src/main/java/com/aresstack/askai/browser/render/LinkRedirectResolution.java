package com.aresstack.askai.browser.render;

/** Outcome of the static search-redirect resolution for one captured link. */
public enum LinkRedirectResolution {
    /** No known redirect wrapper — the raw href IS the target. */
    NOT_A_REDIRECT,
    /** A known wrapper whose direct target was extracted; the target is the navigation candidate. */
    RESOLVED,
    /** A known wrapper whose target could not be extracted — never classified by the wrapper host. */
    UNRESOLVED
}
