package com.aresstack.askai.research.connector;

import java.io.File;

/**
 * The configuration of the ChatGPT-connector face of AskAI: the OAuth client pair, the public origin the
 * Apache reverse proxy serves (TLS terminates THERE — this process listens on plain HTTP), the local
 * listen port and the refresh-token store. Immutable; one instance per server start.
 */
public final class ConnectorConfig {

    /**
     * The MCP endpoint lives at the ROOT: the connector already has a DEDICATED public origin
     * (subdomain), so the ChatGPT URL is simply {@code <publicOrigin>/}. The reverse proxy forwards
     * paths unchanged; /oauth/* and /.well-known/* sit next to it.
     */
    public static final String MCP_PUBLIC_PATH = "/";

    /** Pyloros-style property defaults: the server always has a usable client pair. */
    public static final String DEFAULT_CLIENT_ID = "askai";
    public static final String DEFAULT_CLIENT_SECRET = "change-me";

    private final int port;
    private final String publicOrigin;
    private final String clientId;
    private final String clientSecret;
    private final File refreshTokenStore;
    private final int accessTokenTtlSeconds;
    private final int refreshTokenTtlSeconds;

    public ConnectorConfig(int port, String publicOrigin, String clientId, String clientSecret,
                           File refreshTokenStore) {
        this(port, publicOrigin, clientId, clientSecret, refreshTokenStore, 3600, 2592000);
    }

    public ConnectorConfig(int port, String publicOrigin, String clientId, String clientSecret,
                           File refreshTokenStore, int accessTokenTtlSeconds, int refreshTokenTtlSeconds) {
        this.port = port;
        this.publicOrigin = trimTrailingSlash(publicOrigin);
        this.clientId = clientId == null || clientId.trim().isEmpty()
                ? DEFAULT_CLIENT_ID : clientId.trim();
        this.clientSecret = clientSecret == null || clientSecret.trim().isEmpty()
                ? DEFAULT_CLIENT_SECRET : clientSecret.trim();
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    private static String trimTrailingSlash(String origin) {
        String o = origin == null ? "" : origin.trim();
        return o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
    }

    public int getPort() {
        return port;
    }

    /** e.g. {@code https://askai.current-car.com} — advertised in the OAuth metadata, never bound locally. */
    public String getPublicOrigin() {
        return publicOrigin;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public File getRefreshTokenStore() {
        return refreshTokenStore;
    }

    public int getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public int getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    /**
     * Exactly like Pyloros: the client pair always exists (defaults above), so only the public
     * origin is genuinely required.
     */
    public boolean isComplete() {
        return !publicOrigin.isEmpty();
    }
}
