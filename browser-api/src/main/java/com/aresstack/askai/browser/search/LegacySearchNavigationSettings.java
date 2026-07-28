package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * Engine order, attempts and navigation limits for the legacy browser search. The forbidden flat-anchor
 * fallback is a hard invariant, NOT a setting — no field here (or anywhere) can re-enable it.
 */
public final class LegacySearchNavigationSettings {

    /**
     * Fallback engine URL templates ({@code {query}} placeholder), tried in order AFTER the session's
     * configured primary engine. Only applies behind a public (REGISTERED_NAME) primary engine.
     */
    public final List<String> fallbackEngineTemplates;
    /** Upper bound on engines tried per search (primary + fallbacks). */
    public final int maximumEngineAttempts;
    /** Timeout for a navigation to commit (Playwright default navigation timeout). */
    public final int navigationCommitTimeoutMillis;
    public final EngineSwitchPolicy engineSwitchPolicy;
    /** Statically resolve engine redirect wrappers (Bing /ck/, Google /url, DDG /l/) before domain checks. */
    public final boolean redirectResolutionEnabled;
    /** URLs longer than this are never fed to the redirect resolver (defense against pathological inputs). */
    public final int maximumRedirectUrlLength;
    /** Maximum organic results returned per search. */
    public final int searchResultLimit;
    /** Preferred content language (BCP-47, e.g. "de"); empty = engine default. */
    public final String language;
    /** Preferred region/country code (e.g. "DE"); empty = engine default. */
    public final String country;

    public LegacySearchNavigationSettings(List<String> fallbackEngineTemplates, int maximumEngineAttempts,
                                          int navigationCommitTimeoutMillis,
                                          EngineSwitchPolicy engineSwitchPolicy,
                                          boolean redirectResolutionEnabled, int maximumRedirectUrlLength,
                                          int searchResultLimit, String language, String country) {
        this.fallbackEngineTemplates = Collections.unmodifiableList(fallbackEngineTemplates);
        this.maximumEngineAttempts = maximumEngineAttempts;
        this.navigationCommitTimeoutMillis = navigationCommitTimeoutMillis;
        this.engineSwitchPolicy = engineSwitchPolicy;
        this.redirectResolutionEnabled = redirectResolutionEnabled;
        this.maximumRedirectUrlLength = maximumRedirectUrlLength;
        this.searchResultLimit = searchResultLimit;
        this.language = language;
        this.country = country;
    }
}
