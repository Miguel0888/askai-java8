package com.aresstack.askai.browser;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * URL gate every backend must apply BEFORE fetching: only http/https; {@code file:}, {@code jar:},
 * {@code data:}, {@code javascript:} (and everything else) are rejected; loopback/private/link-local targets
 * are blocked unless explicitly allowed (tests allow them for the local test server). Resolution failures are
 * rejected, not guessed.
 */
public final class UrlSafetyPolicy {

    private final boolean allowPrivateNetworks;

    public UrlSafetyPolicy(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public static UrlSafetyPolicy strict() {
        return new UrlSafetyPolicy(false);
    }

    /** Test/dev policy: private/loopback targets explicitly permitted (schemes stay restricted). */
    public static UrlSafetyPolicy allowingPrivateNetworks() {
        return new UrlSafetyPolicy(true);
    }

    /** @return the parsed URI when safe. @throws BrowserException with a readable reason otherwise. */
    public URI check(String url) throws BrowserException {
        if (url == null || url.trim().isEmpty()) {
            throw new BrowserException("Empty URL.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            throw new BrowserException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BrowserException("Blocked URL scheme: " + (scheme.isEmpty() ? "(none)" : scheme));
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new BrowserException("URL has no host: " + url);
        }
        if (!allowPrivateNetworks) {
            requirePublicHost(host);
        }
        return uri;
    }

    private static void requirePublicHost(String host) throws BrowserException {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException ex) {
            throw new BrowserException("Cannot resolve host: " + host);
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            throw new BrowserException("Blocked private/loopback target: " + host);
        }
    }
}
