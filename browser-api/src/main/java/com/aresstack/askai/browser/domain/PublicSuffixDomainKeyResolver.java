package com.aresstack.askai.browser.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The productive {@link DomainKeyResolver}: registrable domains are formed against a BUNDLED snapshot of
 * multi-label public suffixes ({@code co.uk}, {@code com.au}, …) loaded once from the classpath resource
 * {@code domain/public-suffix-snapshot.txt}. The registrable domain is the longest matching public suffix
 * plus one label; a plain TLD is always a public suffix. The snapshot is deliberately a curated subset of
 * the Public Suffix List — it is NEVER fetched from the network during a research session and can be
 * extended by replacing the resource.
 */
public final class PublicSuffixDomainKeyResolver implements DomainKeyResolver {

    private static final String SNAPSHOT_RESOURCE = "domain/public-suffix-snapshot.txt";

    /** Multi-label public suffixes from the bundled snapshot (single-label TLDs are implicit). */
    private final Set<String> multiLabelSuffixes;

    public PublicSuffixDomainKeyResolver() {
        this(loadSnapshot());
    }

    PublicSuffixDomainKeyResolver(Set<String> multiLabelSuffixes) {
        this.multiLabelSuffixes = Collections.unmodifiableSet(
                new HashSet<String>(multiLabelSuffixes == null
                        ? Collections.<String>emptySet() : multiLabelSuffixes));
    }

    @Override
    public DomainIdentity resolve(String urlOrHost) {
        String host = hostOf(urlOrHost);
        if (host.isEmpty()) {
            return new DomainIdentity("", "", HostKind.SINGLE_LABEL_HOST);
        }
        if (isIpLiteral(host)) {
            return new DomainIdentity(host, host, HostKind.IP_LITERAL);
        }
        if ("localhost".equals(host)) {
            return new DomainIdentity(host, host, HostKind.LOCAL_HOST);
        }
        if (host.indexOf('.') < 0) {
            return new DomainIdentity(host, host, HostKind.SINGLE_LABEL_HOST);
        }
        return new DomainIdentity(host, registrableDomainOf(host), HostKind.REGISTERED_NAME);
    }

    /** Longest matching public suffix + one label; the bare TLD is always a suffix. */
    private String registrableDomainOf(String host) {
        String[] labels = host.split("\\.");
        // Try the longest candidate suffixes first: labels[i..] with i ascending keeps suffixes longest.
        for (int i = 1; i < labels.length - 1; i++) {
            String candidate = join(labels, i);
            if (multiLabelSuffixes.contains(candidate)) {
                return labels[i - 1] + "." + candidate;
            }
        }
        // Default rule: the last label is the public suffix → registrable = last two labels.
        return labels.length < 2 ? host : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private static String join(String[] labels, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    /** Extract the bare host (no scheme, no port, no path) from a URL or return the input as host. */
    public static String hostOf(String urlOrHost) {
        String value = urlOrHost == null ? "" : urlOrHost.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[")) { // IPv6 literal with optional port: [::1]:8080
            int end = value.indexOf(']');
            return end > 0 ? value.substring(0, end + 1) : value;
        }
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(0, colon) : value;
    }

    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[") || host.matches("[0-9a-f:]*:[0-9a-f:]*")) {
            return true; // IPv6
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static Set<String> loadSnapshot() {
        Set<String> suffixes = new HashSet<String>();
        InputStream in = PublicSuffixDomainKeyResolver.class.getClassLoader()
                .getResourceAsStream(SNAPSHOT_RESOURCE);
        if (in == null) {
            return suffixes; // missing snapshot degrades to the default last-label rule, never crashes
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.indexOf('.') > 0) {
                    suffixes.add(trimmed);
                }
            }
        } catch (IOException ignored) {
            // partial snapshot is still usable
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return suffixes;
    }
}
