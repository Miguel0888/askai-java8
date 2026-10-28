package com.aresstack.askai.research.connector;

import java.util.Map;

/**
 * The authentication face of the public connector. Two implementations exist:
 * the legacy LOCAL authorization server ({@link ConnectorOAuthService}) and the
 * CENTRAL mode ({@link CentralJwtAuthentication}) where the AresStack webserver
 * issues RFC 9068 JWTs and AskAI only validates them offline.
 */
public interface ConnectorAuthentication {

    /** Validate the Authorization header of a protected request. */
    boolean isBearerAuthorized(String authorizationHeader);

    /** Whether THIS process serves /oauth/* and the authorization-server metadata. */
    boolean handlesOAuthLocally();

    /**
     * The external issuer advertised in the protected-resource metadata, or
     * {@code null} when this process is its own authorization server.
     */
    String externalIssuer();

    /** Local-mode authorization redirect; central mode rejects with {@link OAuthError}. */
    String authorize(String responseType, String clientId, String redirectUri, String state,
                     String scope, String codeChallenge, String codeChallengeMethod);

    /** Local-mode token exchange; central mode rejects with {@link OAuthError}. */
    Map<String, Object> token(String clientId, String clientSecret, String grantType, String code,
                              String refreshToken, String redirectUri, String codeVerifier);
}
