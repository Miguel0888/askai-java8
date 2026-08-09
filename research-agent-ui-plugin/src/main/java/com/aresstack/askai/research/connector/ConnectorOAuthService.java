package com.aresstack.askai.research.connector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OAuth 2.0 authorization server for the ChatGPT connector — a Java-8 port of the proven Pyloros
 * OAuthService: authorization_code + PKCE (S256, compatibility mode when the client sends none),
 * refresh_token grant, short replay windows for duplicate parallel token exchanges, and a persisted
 * refresh-token store so a ChatGPT connection survives AskAI restarts. Redirect URIs are restricted to
 * the ChatGPT domains. All state except refresh tokens is in-memory.
 */
public final class ConnectorOAuthService {

    /** Test seam for time. */
    public interface TimeSource {
        long nowMillis();
    }

    private static final int AUTHORIZATION_CODE_TTL_SECONDS = 300;
    private static final int REPLAY_CACHE_TTL_SECONDS = 10;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConnectorConfig config;
    private final TimeSource time;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, AuthorizationCode> authorizationCodes = new LinkedHashMap<String, AuthorizationCode>();
    private final Map<String, Long> accessTokens = new LinkedHashMap<String, Long>();
    private final Map<String, RefreshTokenState> refreshTokens = new LinkedHashMap<String, RefreshTokenState>();
    private final Map<String, ReplayEntry> replayCache = new LinkedHashMap<String, ReplayEntry>();

    public ConnectorOAuthService(ConnectorConfig config) {
        this(config, new TimeSource() {
            public long nowMillis() {
                return System.currentTimeMillis();
            }
        });
    }

    public ConnectorOAuthService(ConnectorConfig config, TimeSource time) {
        this.config = config;
        this.time = time;
        loadRefreshTokens();
    }

    /**
     * GET /oauth/authorize — validates client + redirect target and hands out a short-lived code.
     *
     * @return the redirect Location (redirect_uri + code + state)
     */
    public synchronized String authorize(String responseType, String clientId, String redirectUri,
                                         String state, String scope, String codeChallenge,
                                         String codeChallengeMethod) {
        if (!"code".equals(responseType)) {
            throw new OAuthError(400, "unsupported_response_type");
        }
        if (clientId == null || !clientId.equals(config.getClientId())) {
            throw new OAuthError(400, "invalid_client");
        }
        if (redirectUri == null || redirectUri.trim().isEmpty()) {
            throw new OAuthError(400, "invalid_request", "Missing redirect_uri");
        }
        if (!isAllowedRedirectUri(redirectUri)) {
            throw new OAuthError(400, "invalid_request", "Unsupported redirect_uri");
        }
        String code = opaque();
        authorizationCodes.put(code, new AuthorizationCode(clientId, redirectUri,
                scope == null || scope.trim().isEmpty() ? "mcp" : scope,
                codeChallenge, codeChallengeMethod,
                time.nowMillis() + AUTHORIZATION_CODE_TTL_SECONDS * 1000L));
        StringBuilder location = new StringBuilder(redirectUri)
                .append(redirectUri.contains("?") ? '&' : '?')
                .append("code=").append(urlEncode(code));
        if (state != null && !state.trim().isEmpty()) {
            location.append("&state=").append(urlEncode(state));
        }
        return location.toString();
    }

    /** POST /oauth/token — authorization_code and refresh_token grants. */
    public synchronized Map<String, Object> token(String clientId, String clientSecret, String grantType,
                                                  String code, String refreshToken, String redirectUri,
                                                  String codeVerifier) {
        if (clientId == null || !clientId.equals(config.getClientId())) {
            throw new OAuthError(401, "invalid_client");
        }
        // Empty configured secret = PUBLIC client (ChatGPT's dynamic registration): PKCE carries the
        // security; a configured secret is enforced strictly.
        if (!config.getClientSecret().isEmpty()
                && !config.getClientSecret().equals(clientSecret == null ? "" : clientSecret)) {
            throw new OAuthError(401, "invalid_client");
        }
        cleanupExpired();
        if ("authorization_code".equals(grantType)) {
            return exchangeCode(clientId, code, redirectUri, codeVerifier);
        }
        if ("refresh_token".equals(grantType)) {
            return exchangeRefresh(clientId, refreshToken);
        }
        throw new OAuthError(400, "unsupported_grant_type");
    }

    private Map<String, Object> exchangeCode(String clientId, String code, String redirectUri,
                                             String codeVerifier) {
        AuthorizationCode authorization = authorizationCodes.remove(code);
        if (authorization == null) {
            return replay(clientId, code, redirectUri, codeVerifier);
        }
        long now = time.nowMillis();
        if (authorization.expiresAtMillis < now) {
            throw new OAuthError(400, "invalid_grant", "Authorization code expired");
        }
        if (!authorization.clientId.equals(clientId)) {
            throw new OAuthError(400, "invalid_grant");
        }
        if (redirectUri != null && !redirectUri.equals(authorization.redirectUri)) {
            throw new OAuthError(400, "invalid_grant", "redirect_uri mismatch");
        }
        if (!isPkceValid(codeVerifier, authorization)) {
            throw new OAuthError(400, "invalid_grant", "PKCE verification failed");
        }
        String accessToken = opaque();
        String newRefreshToken = opaque();
        accessTokens.put(accessToken, now + config.getAccessTokenTtlSeconds() * 1000L);
        refreshTokens.put(newRefreshToken, new RefreshTokenState(clientId, authorization.scope,
                now + config.getRefreshTokenTtlSeconds() * 1000L));
        saveRefreshTokens();
        Map<String, Object> response = tokenResponse(accessToken, newRefreshToken, authorization.scope);
        // tolerate ChatGPT's duplicate parallel exchanges of the SAME code within a short window
        replayCache.put(code, new ReplayEntry(response, clientId, redirectUri, codeVerifier,
                now + REPLAY_CACHE_TTL_SECONDS * 1000L));
        return response;
    }

    private Map<String, Object> replay(String clientId, String code, String redirectUri,
                                       String codeVerifier) {
        ReplayEntry entry = replayCache.get(code);
        if (entry == null || entry.expiresAtMillis < time.nowMillis()
                || !entry.clientId.equals(clientId)
                || (redirectUri != null && !redirectUri.equals(entry.redirectUri))
                || !equalsNullable(codeVerifier, entry.codeVerifier)) {
            throw new OAuthError(400, "invalid_grant");
        }
        return entry.response;
    }

    private Map<String, Object> exchangeRefresh(String clientId, String refreshToken) {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new OAuthError(400, "invalid_grant");
        }
        RefreshTokenState state = refreshTokens.get(refreshToken);
        long now = time.nowMillis();
        if (state == null || state.expiresAtMillis < now || !state.clientId.equals(clientId)) {
            if (state != null) {
                refreshTokens.remove(refreshToken);
                saveRefreshTokens();
            }
            throw new OAuthError(400, "invalid_grant");
        }
        String accessToken = opaque();
        accessTokens.put(accessToken, now + config.getAccessTokenTtlSeconds() * 1000L);
        // no rotation (like the proven Pyloros default): the refresh token stays valid until its TTL
        return tokenResponse(accessToken, refreshToken, state.scope);
    }

    private Map<String, Object> tokenResponse(String accessToken, String refreshToken, String scope) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("access_token", accessToken);
        body.put("token_type", "Bearer");
        body.put("expires_in", config.getAccessTokenTtlSeconds());
        body.put("refresh_token", refreshToken);
        body.put("scope", scope);
        return body;
    }

    /**
     * RFC 7591 dynamic client registration: ChatGPT registers itself and is ASSIGNED our one client —
     * no pre-shared credentials needed. Public client unless a secret is configured.
     */
    public Map<String, Object> registerClient() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.getClientId());
        if (!config.getClientSecret().isEmpty()) {
            body.put("client_secret", config.getClientSecret());
        }
        body.put("token_endpoint_auth_method",
                config.getClientSecret().isEmpty() ? "none" : "client_secret_post");
        body.put("grant_types", new String[]{"authorization_code", "refresh_token"});
        body.put("response_types", new String[]{"code"});
        return body;
    }

    /** Bearer check for MCP requests. @return true when the Authorization header carries a live token. */
    public synchronized boolean isBearerAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        Long expiresAt = accessTokens.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < time.nowMillis()) {
            accessTokens.remove(token);
            return false;
        }
        return true;
    }

    private static boolean isAllowedRedirectUri(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && ("chatgpt.com".equalsIgnoreCase(host) || "chat.openai.com".equalsIgnoreCase(host));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean isPkceValid(String codeVerifier, AuthorizationCode authorization) {
        if (authorization.codeChallenge == null || authorization.codeChallenge.trim().isEmpty()) {
            return true; // compatibility mode: the client never sent a challenge
        }
        if (codeVerifier == null || codeVerifier.trim().isEmpty()) {
            return false;
        }
        if ("S256".equals(authorization.codeChallengeMethod)) {
            return base64Url(sha256(codeVerifier)).equals(authorization.codeChallenge);
        }
        return codeVerifier.equals(authorization.codeChallenge);
    }

    private void cleanupExpired() {
        long now = time.nowMillis();
        boolean refreshChanged = false;
        for (Iterator<Map.Entry<String, RefreshTokenState>> it = refreshTokens.entrySet().iterator();
                it.hasNext();) {
            if (it.next().getValue().expiresAtMillis < now) {
                it.remove();
                refreshChanged = true;
            }
        }
        for (Iterator<Map.Entry<String, ReplayEntry>> it = replayCache.entrySet().iterator();
                it.hasNext();) {
            if (it.next().getValue().expiresAtMillis < now) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, Long>> it = accessTokens.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
        if (refreshChanged) {
            saveRefreshTokens();
        }
    }

    private void loadRefreshTokens() {
        File store = config.getRefreshTokenStore();
        if (store == null || !store.isFile()) {
            return;
        }
        try {
            InputStreamReader reader = new InputStreamReader(new FileInputStream(store), "UTF-8");
            try {
                JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray tokens = document.getAsJsonArray("tokens");
                long now = time.nowMillis();
                for (int i = 0; tokens != null && i < tokens.size(); i++) {
                    JsonObject entry = tokens.get(i).getAsJsonObject();
                    String token = entry.get("token").getAsString();
                    long expiresAt = entry.get("expiresAtMillis").getAsLong();
                    if (expiresAt > now) {
                        refreshTokens.put(token, new RefreshTokenState(
                                entry.get("clientId").getAsString(),
                                entry.get("scope").getAsString(), expiresAt));
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception unreadable) {
            // a broken store must never block startup; the user re-authorizes once
        }
    }

    private void saveRefreshTokens() {
        File store = config.getRefreshTokenStore();
        if (store == null) {
            return;
        }
        try {
            File parent = store.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            JsonObject document = new JsonObject();
            document.addProperty("version", 1);
            JsonArray tokens = new JsonArray();
            for (Map.Entry<String, RefreshTokenState> entry : refreshTokens.entrySet()) {
                JsonObject item = new JsonObject();
                item.addProperty("token", entry.getKey());
                item.addProperty("clientId", entry.getValue().clientId);
                item.addProperty("scope", entry.getValue().scope);
                item.addProperty("expiresAtMillis", entry.getValue().expiresAtMillis);
                tokens.add(item);
            }
            document.add("tokens", tokens);
            File temp = new File(store.getParentFile(), store.getName() + ".tmp");
            Writer writer = new OutputStreamWriter(new FileOutputStream(temp), "UTF-8");
            try {
                GSON.toJson(document, writer);
            } finally {
                writer.close();
            }
            if (!temp.renameTo(store)) {
                store.delete();
                temp.renameTo(store);
            }
        } catch (Exception unwritable) {
            // persistence is best-effort; in-memory state keeps the current session working
        }
    }

    private String opaque() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static final class AuthorizationCode {
        final String clientId;
        final String redirectUri;
        final String scope;
        final String codeChallenge;
        final String codeChallengeMethod;
        final long expiresAtMillis;

        AuthorizationCode(String clientId, String redirectUri, String scope, String codeChallenge,
                          String codeChallengeMethod, long expiresAtMillis) {
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.scope = scope;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class RefreshTokenState {
        final String clientId;
        final String scope;
        final long expiresAtMillis;

        RefreshTokenState(String clientId, String scope, long expiresAtMillis) {
            this.clientId = clientId;
            this.scope = scope;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class ReplayEntry {
        final Map<String, Object> response;
        final String clientId;
        final String redirectUri;
        final String codeVerifier;
        final long expiresAtMillis;

        ReplayEntry(Map<String, Object> response, String clientId, String redirectUri,
                    String codeVerifier, long expiresAtMillis) {
            this.response = response;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.codeVerifier = codeVerifier;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
