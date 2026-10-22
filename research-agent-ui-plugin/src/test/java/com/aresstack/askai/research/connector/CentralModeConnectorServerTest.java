package com.aresstack.askai.research.connector;

import com.aresstack.anauthentia.jwt.JwtAccessTokenIssuer;
import com.aresstack.anauthentia.jwt.JwtAccessTokenValidator;
import com.aresstack.anauthentia.jwt.JwtKeyRing;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * CENTRAL mode over real HTTP: the connector validates RS256 JWTs of the
 * central AresStack issuer offline, serves NO local OAuth endpoints, and the
 * protected-resource metadata points at the central authorization server.
 */
public class CentralModeConnectorServerTest {

    private static final String ISSUER = "https://askai.example.com";
    private static final String AUDIENCE = ISSUER + "/";

    private final JwtKeyRing keyRing = JwtKeyRing.generate("test-key");
    private final JwtAccessTokenIssuer tokenIssuer =
            new JwtAccessTokenIssuer(ISSUER, AUDIENCE, keyRing);

    private ChatGptConnectorServer server;
    private String base;

    @Before
    public void start() throws Exception {
        ConnectorConfig config = new ConnectorConfig(
                0, ISSUER, "askai", "secret", null, ISSUER);
        assertTrue(config.isCentralMode());
        CentralJwtAuthentication authentication = new CentralJwtAuthentication(ISSUER,
                new JwtAccessTokenValidator(ISSUER, AUDIENCE, keyRing.publicJwkSet()));
        server = new ChatGptConnectorServer(config, authentication,
                new ChatGptConnectorServer.ToolProvider() {
                    public List<McpToolContribution> tools() {
                        return Collections.emptyList();
                    }
                });
        server.start();
        base = "http://127.0.0.1:" + server.boundPort();
    }

    @After
    public void stop() {
        server.stop();
    }

    private String validToken() {
        Instant now = Instant.now();
        return tokenIssuer.generate("chatgpt", "mcp", now, now.plus(Duration.ofMinutes(5)));
    }

    @Test
    public void validCentralJwtReachesTheMcpEndpoint() throws Exception {
        Response response = post("/", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",
                "Bearer " + validToken());
        assertEquals(200, response.status);
        JsonObject body = JsonParser.parseString(response.body).getAsJsonObject();
        assertEquals("askai", body.getAsJsonObject("result")
                .getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    public void missingWrongScopeAndExpiredTokensAreRejected() throws Exception {
        assertEquals(401, post("/", "{}", null).status);

        Instant now = Instant.now();
        String wrongScope = tokenIssuer.generate("chatgpt", "other",
                now, now.plus(Duration.ofMinutes(5)));
        assertEquals(401, post("/", "{}", "Bearer " + wrongScope).status);

        Instant past = now.minus(Duration.ofHours(1));
        String expired = tokenIssuer.generate("chatgpt", "mcp",
                past, past.plus(Duration.ofMinutes(5)));
        Response rejected = post("/", "{}", "Bearer " + expired);
        assertEquals(401, rejected.status);
        assertNotNull(rejected.wwwAuthenticate);
        assertTrue(rejected.wwwAuthenticate.contains("invalid_token"));
    }

    @Test
    public void localOAuthEndpointsAreGoneAndPointAtTheCentralIssuer() throws Exception {
        Response token = post("/oauth/token", "grant_type=authorization_code&code=x", null);
        assertEquals(404, token.status);
        assertTrue(token.body.contains(ISSUER));

        assertEquals(404, get("/.well-known/oauth-authorization-server").status);
        assertEquals(404, get("/oauth/authorize").status);
    }

    @Test
    public void protectedResourceMetadataAdvertisesTheCentralAuthorizationServer() throws Exception {
        Response response = get("/.well-known/oauth-protected-resource");
        assertEquals(200, response.status);
        JsonObject body = JsonParser.parseString(response.body).getAsJsonObject();
        assertEquals(ISSUER, body.getAsJsonArray("authorization_servers").get(0).getAsString());
        assertEquals(ISSUER + "/", body.get("resource").getAsString());
    }

    // ------------------------------------------------------------------

    private static final class Response {
        final int status;
        final String body;
        final String wwwAuthenticate;

        Response(int status, String body, String wwwAuthenticate) {
            this.status = status;
            this.body = body;
            this.wwwAuthenticate = wwwAuthenticate;
        }
    }

    private Response get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        return read(connection);
    }

    private Response post(String path, String body, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        OutputStream out = connection.getOutputStream();
        out.write(body.getBytes("UTF-8"));
        out.close();
        return read(connection);
    }

    private static Response read(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[4096];
            int readCount;
            while ((readCount = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, readCount);
            }
            stream.close();
        }
        return new Response(status, buffer.toString("UTF-8"),
                connection.getHeaderField("WWW-Authenticate"));
    }
}
