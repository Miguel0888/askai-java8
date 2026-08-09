package com.aresstack.askai.research.connector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The ChatGPT-connector OAuth server: the full authorization_code+PKCE round trip, the refresh grant,
 * the replay window for ChatGPT's duplicate parallel exchanges, and the persisted refresh store that
 * keeps a connection alive across AskAI restarts.
 */
public class ConnectorOAuthServiceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private long now = 1000000L;
    private final ConnectorOAuthService.TimeSource clock = new ConnectorOAuthService.TimeSource() {
        public long nowMillis() {
            return now;
        }
    };

    private ConnectorConfig config(File store) {
        return new ConnectorConfig(0, "https://askai.example.com", "askai", "secret", store);
    }

    private static String codeFromLocation(String location) throws Exception {
        int start = location.indexOf("code=") + "code=".length();
        int end = location.indexOf('&', start);
        return URLDecoder.decode(end < 0 ? location.substring(start) : location.substring(start, end),
                "UTF-8");
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8")));
    }

    @Test
    public void theFullPkceRoundTripYieldsALiveBearerToken() throws Exception {
        ConnectorOAuthService oauth = new ConnectorOAuthService(config(null), clock);
        String location = oauth.authorize("code", "askai", "https://chatgpt.com/connector_platform_oauth_redirect",
                "st4te", "mcp", challenge("verifier-123"), "S256");
        assertTrue(location, location.startsWith("https://chatgpt.com/"));
        assertTrue(location, location.contains("state=st4te"));

        Map<String, Object> token = oauth.token("askai", "secret", "authorization_code",
                codeFromLocation(location), null,
                "https://chatgpt.com/connector_platform_oauth_redirect", "verifier-123");
        assertEquals("Bearer", token.get("token_type"));
        assertTrue(oauth.isBearerAuthorized("Bearer " + token.get("access_token")));
        assertFalse(oauth.isBearerAuthorized("Bearer wrong"));
        assertFalse(oauth.isBearerAuthorized(null));
    }

    @Test
    public void wrongPkceVerifierAndWrongSecretAndForeignRedirectAreRejected() throws Exception {
        ConnectorOAuthService oauth = new ConnectorOAuthService(config(null), clock);
        try {
            oauth.authorize("code", "askai", "https://evil.example.com/cb", null, "mcp", null, null);
            fail("foreign redirect_uri accepted");
        } catch (OAuthError expected) {
            assertEquals("invalid_request", expected.getError());
        }
        String location = oauth.authorize("code", "askai",
                "https://chatgpt.com/cb", null, "mcp", challenge("right"), "S256");
        String code = codeFromLocation(location);
        try {
            oauth.token("askai", "WRONG", "authorization_code", code, null, "https://chatgpt.com/cb", "right");
            fail("wrong client secret accepted");
        } catch (OAuthError expected) {
            assertEquals(401, expected.getStatusCode());
        }
        try {
            oauth.token("askai", "secret", "authorization_code", code, null, "https://chatgpt.com/cb", "wrong");
            fail("wrong PKCE verifier accepted");
        } catch (OAuthError expected) {
            assertEquals("invalid_grant", expected.getError());
        }
    }

    @Test
    public void expiredCodesAreRejectedAndDuplicateParallelExchangesAreToleratedBriefly() throws Exception {
        ConnectorOAuthService oauth = new ConnectorOAuthService(config(null), clock);
        String code = codeFromLocation(oauth.authorize("code", "askai",
                "https://chatgpt.com/cb", null, "mcp", null, null));
        now += 301 * 1000L;
        try {
            oauth.token("askai", "secret", "authorization_code", code, null, "https://chatgpt.com/cb", null);
            fail("expired code accepted");
        } catch (OAuthError expected) {
            assertEquals("invalid_grant", expected.getError());
        }

        String code2 = codeFromLocation(oauth.authorize("code", "askai",
                "https://chatgpt.com/cb", null, "mcp", null, null));
        Map<String, Object> first = oauth.token("askai", "secret", "authorization_code", code2, null,
                "https://chatgpt.com/cb", null);
        Map<String, Object> replayed = oauth.token("askai", "secret", "authorization_code", code2, null,
                "https://chatgpt.com/cb", null);
        assertEquals(first.get("access_token"), replayed.get("access_token"));
        now += 11 * 1000L; // replay window over
        try {
            oauth.token("askai", "secret", "authorization_code", code2, null, "https://chatgpt.com/cb", null);
            fail("code replay accepted after the window");
        } catch (OAuthError expected) {
            assertEquals("invalid_grant", expected.getError());
        }
    }

    @Test
    public void emptyConfiguredCredentialsFallBackToThePylorosStyleDefaults() throws Exception {
        ConnectorConfig defaults = new ConnectorConfig(0, "https://askai.example.com", "", "", null);
        assertEquals("askai", defaults.getClientId());
        assertEquals("change-me", defaults.getClientSecret());
        assertTrue(defaults.isComplete());

        ConnectorOAuthService oauth = new ConnectorOAuthService(defaults, clock);
        String code = codeFromLocation(oauth.authorize("code", "askai",
                "https://chatgpt.com/cb", null, "mcp", challenge("v1"), "S256"));
        Map<String, Object> token = oauth.token("askai", "change-me", "authorization_code", code, null,
                "https://chatgpt.com/cb", "v1");
        assertTrue(oauth.isBearerAuthorized("Bearer " + token.get("access_token")));
    }

    @Test
    public void theRefreshGrantIssuesANewAccessTokenAndSurvivesARestartViaTheStore() throws Exception {
        File store = new File(temp.getRoot(), "refresh.json");
        ConnectorOAuthService oauth = new ConnectorOAuthService(config(store), clock);
        String code = codeFromLocation(oauth.authorize("code", "askai",
                "https://chatgpt.com/cb", null, "mcp", null, null));
        Map<String, Object> token = oauth.token("askai", "secret", "authorization_code", code, null,
                "https://chatgpt.com/cb", null);
        String refreshToken = (String) token.get("refresh_token");

        // a NEW service instance (= app restart) accepts the persisted refresh token
        ConnectorOAuthService restarted = new ConnectorOAuthService(config(store), clock);
        Map<String, Object> refreshed = restarted.token("askai", "secret", "refresh_token",
                null, refreshToken, null, null);
        assertNotEquals(token.get("access_token"), refreshed.get("access_token"));
        assertTrue(restarted.isBearerAuthorized("Bearer " + refreshed.get("access_token")));

        // an expired access token stops authorizing
        now += 3601 * 1000L;
        assertFalse(restarted.isBearerAuthorized("Bearer " + refreshed.get("access_token")));
    }
}
