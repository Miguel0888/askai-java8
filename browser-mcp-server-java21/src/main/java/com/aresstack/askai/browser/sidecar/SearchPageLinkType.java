package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.domain.DomainIdentity;

import java.util.Locale;

/**
 * Typed classification of a SERP link (after redirect resolution). Only {@link #ORGANIC_RESULT} becomes an
 * initial URL candidate; SEARCH_VERTICAL and PAGINATION are MODELED for later policies (video search,
 * second result page) but deliberately not executed yet. This stays SERP-strategy internal — the research
 * loop never sees these types.
 */
enum SearchPageLinkType {

    ORGANIC_RESULT,
    SEARCH_VERTICAL,
    PAGINATION,
    QUERY_REFINEMENT,
    ACCOUNT_OR_SETTINGS,
    LEGAL_OR_HELP,
    ADVERTISEMENT,
    UNKNOWN_INTERNAL;

    /** Classify one link by its RESOLVED target against the engine's identity (never the wrapper). */
    static SearchPageLinkType classify(String resolvedUrl, String linkText, DomainIdentity provider,
                                       com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys) {
        DomainIdentity target = domainKeys.resolve(resolvedUrl);
        if (!target.sameFamily(provider)) {
            return ORGANIC_RESULT; // external family → candidate (the result-block analysis refines later)
        }
        String path = WebSearchProvider.OrganicResultSearchProvider.pathOf(
                resolvedUrl.toLowerCase(Locale.ROOT));
        String text = linkText == null ? "" : linkText.toLowerCase(Locale.ROOT).trim();
        if (containsSegment(path, "images") || containsSegment(path, "videos")
                || containsSegment(path, "shopping") || containsSegment(path, "maps")
                || containsSegment(path, "news")
                || text.equals("bilder") || text.equals("images") || text.equals("videos")
                || text.equals("shopping") || text.equals("maps") || text.equals("news")
                || text.equals("karten")) {
            return SEARCH_VERTICAL;
        }
        if (path.contains("/aclk") || path.startsWith("/ads") || containsSegment(path, "ad")) {
            return ADVERTISEMENT;
        }
        if (containsSegment(path, "account") || containsSegment(path, "settings")
                || containsSegment(path, "preferences") || containsSegment(path, "profile")
                || containsSegment(path, "signin") || containsSegment(path, "login")
                || containsSegment(path, "rewards")
                || text.contains("sign in") || text.contains("anmelden")
                || text.contains("einstellungen") || text.contains("konto")) {
            return ACCOUNT_OR_SETTINGS;
        }
        if (containsSegment(path, "privacy") || containsSegment(path, "legal")
                || containsSegment(path, "help") || containsSegment(path, "support")
                || containsSegment(path, "terms") || containsSegment(path, "about")
                || text.contains("datenschutz") || text.contains("impressum")
                || text.contains("privacy") || text.contains("hilfe") || text.contains("help")) {
            return LEGAL_OR_HELP;
        }
        if (hasQueryParameter(resolvedUrl, "first") || hasQueryParameter(resolvedUrl, "start")
                || hasQueryParameter(resolvedUrl, "page")
                || text.matches("\\d+") || text.equals("next") || text.equals("weiter")
                || text.startsWith("nächste") || text.equals("previous") || text.startsWith("zurück")) {
            return PAGINATION;
        }
        if (containsSegment(path, "search") && hasQueryParameter(resolvedUrl, "q")) {
            return QUERY_REFINEMENT; // related searches on the engine itself
        }
        return UNKNOWN_INTERNAL;
    }

    private static boolean containsSegment(String path, String segment) {
        return path.contains("/" + segment + "/") || path.endsWith("/" + segment)
                || path.contains("/" + segment + "?");
    }

    private static boolean hasQueryParameter(String url, String name) {
        return SearchRedirectResolver.queryParameter(url, name) != null;
    }
}
