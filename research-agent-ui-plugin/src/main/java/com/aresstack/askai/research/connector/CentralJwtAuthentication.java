package com.aresstack.askai.research.connector;

import com.aresstack.anauthentia.jwt.JwtAccessToken;
import com.aresstack.anauthentia.jwt.JwtAccessTokenValidator;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * CENTRAL mode: the AresStack webserver is the authorization server; AskAI only
 * validates its RS256 access tokens offline against the issuer's JWKS (cached,
 * refreshed on unknown kid). This process serves NO OAuth endpoints — the
 * reverse proxy routes /oauth/* and /.well-known/* to the central service, and
 * a direct hit on this port is answered with a clear refusal.
 */
public final class CentralJwtAuthentication implements ConnectorAuthentication {

    static final String REQUIRED_SCOPE = "mcp";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String issuer;
    private final JwtAccessTokenValidator validator;

    public CentralJwtAuthentication(String issuer, String audience) {
        this(issuer, buildValidator(issuer, audience));
    }

    /** Test seam: inject a validator with a known key set. */
    CentralJwtAuthentication(String issuer, JwtAccessTokenValidator validator) {
        this.issuer = issuer;
        this.validator = validator;
    }

    private static JwtAccessTokenValidator buildValidator(String issuer, String audience) {
        try {
            return JwtAccessTokenValidator.forRemoteJwks(issuer, audience,
                    new URL(issuer + "/.well-known/jwks.json"));
        } catch (MalformedURLException invalid) {
            throw new IllegalArgumentException("Invalid central issuer URL: " + issuer, invalid);
        }
    }

    public boolean isBearerAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        JwtAccessToken validated = validator.validate(token).orElse(null);
        return validated != null && validated.hasScope(REQUIRED_SCOPE);
    }

    public boolean handlesOAuthLocally() {
        return false;
    }

    public String externalIssuer() {
        return issuer;
    }

    public String authorize(String responseType, String clientId, String redirectUri, String state,
                            String scope, String codeChallenge, String codeChallengeMethod) {
        throw centralModeRefusal();
    }

    public Map<String, Object> token(String clientId, String clientSecret, String grantType,
                                     String code, String refreshToken, String redirectUri,
                                     String codeVerifier) {
        throw centralModeRefusal();
    }

    private OAuthError centralModeRefusal() {
        return new OAuthError(404, "invalid_request",
                "OAuth is handled by the central authorization server at " + issuer);
    }
}
