package com.aresstack.askai.research.connector;

import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.research.mcp.ResearchBotControlEndpoint;
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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The public ChatGPT-connector face over REAL HTTP: discovery metadata, the OAuth handshake, Bearer
 * enforcement on the MCP endpoint and the JSON-RPC bridge onto the session gateway (the same three
 * driving tools the loopback bot endpoint serves).
 */
public class ChatGptConnectorServerTest {

    private ChatGptConnectorServer server;
    private String base;

    private final ResearchBotControlEndpoint.SessionGateway gateway =
            new ResearchBotControlEndpoint.SessionGateway() {
                public String execute(String command, String arguments) {
                    return "handled: " + command + "/" + arguments;
                }

                public String describeState() {
                    return "phase=scoping";
                }

                public String describeHistory(boolean raw) {
                    return raw ? "raw-history" : "summary-history";
                }
            };

    @Before
    public void start() throws Exception {
        ConnectorConfig config = new ConnectorConfig(0, "https://askai.example.com", "askai", "secret", null);
        server = new ChatGptConnectorServer(config, new ConnectorOAuthService(config),
                new ChatGptConnectorServer.ToolProvider() {
                    public List<McpToolContribution> tools() {
                        return ResearchBotControlEndpoint.drivingTools(gateway);
                    }
                });
        server.start();
        base = "http://127.0.0.1:" + server.boundPort();
    }

    @After
    public void stop() {
        server.stop();
    }

    @Test
    public void theDiscoveryMetadataAdvertisesTheOAuthEndpointsOnThePublicOrigin() throws Exception {
        JsonObject metadata = getJson(base + "/.well-known/oauth-authorization-server", null);
        assertEquals("https://askai.example.com", metadata.get("issuer").getAsString());
        assertEquals("https://askai.example.com/oauth/authorize",
                metadata.get("authorization_endpoint").getAsString());
        assertEquals("https://askai.example.com/oauth/token", metadata.get("token_endpoint").getAsString());

        JsonObject resource = getJson(base + "/.well-known/oauth-protected-resource", null);
        assertEquals("https://askai.example.com/", resource.get("resource").getAsString());
    }

    @Test
    public void theMcpEndpointDemandsABearerTokenAndTheHandshakeProducesOne() throws Exception {
        HttpURLConnection unauthorized = (HttpURLConnection) new URL(base + "/").openConnection();
        unauthorized.setRequestMethod("POST");
        unauthorized.setDoOutput(true);
        write(unauthorized, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertEquals(401, unauthorized.getResponseCode());
        assertTrue(String.valueOf(unauthorized.getHeaderField("WWW-Authenticate")).contains("invalid_token"));

        String token = obtainAccessToken();
        JsonObject initialized = rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertEquals("2025-03-26",
                initialized.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals("askai", initialized.getAsJsonObject("result")
                .getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    public void toolsListAndToolsCallDriveTheSessionGateway() throws Exception {
        String token = obtainAccessToken();
        JsonObject listed = rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        String tools = listed.getAsJsonObject("result").getAsJsonArray("tools").toString();
        assertTrue(tools, tools.contains("run_command"));
        assertTrue(tools, tools.contains("session_state"));
        assertTrue(tools, tools.contains("chat_history"));
        assertTrue(tools, tools.contains("inputSchema"));

        JsonObject called = rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_command\","
                + "\"arguments\":{\"command\":\"search\",\"arguments\":\"wearables\"}}}");
        String text = called.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
        assertEquals("handled: search/wearables", text);

        JsonObject history = rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"chat_history\",\"arguments\":{\"raw\":\"true\"}}}");
        assertEquals("raw-history", history.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString());

        JsonObject unknown = rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\"}}");
        assertTrue(unknown.has("error"));
    }

    /** The real handshake: authorize (302 with code) → token (JSON with access_token). */
    private String obtainAccessToken() throws Exception {
        String redirect = "https://chatgpt.com/connector_platform_oauth_redirect";
        HttpURLConnection authorize = (HttpURLConnection) new URL(base
                + "/oauth/authorize?response_type=code&client_id=askai&redirect_uri="
                + URLEncoder.encode(redirect, "UTF-8") + "&state=s1").openConnection();
        authorize.setInstanceFollowRedirects(false);
        assertEquals(302, authorize.getResponseCode());
        String location = authorize.getHeaderField("Location");
        int start = location.indexOf("code=") + "code=".length();
        int end = location.indexOf('&', start);
        String code = URLDecoder.decode(end < 0 ? location.substring(start)
                : location.substring(start, end), "UTF-8");

        HttpURLConnection token = (HttpURLConnection) new URL(base + "/oauth/token").openConnection();
        token.setRequestMethod("POST");
        token.setDoOutput(true);
        token.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        write(token, "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(redirect, "UTF-8")
                + "&client_id=askai&client_secret=secret");
        assertEquals(200, token.getResponseCode());
        return JsonParser.parseString(read(token.getInputStream()))
                .getAsJsonObject().get("access_token").getAsString();
    }

    private JsonObject rpc(String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        write(connection, body);
        assertEquals(200, connection.getResponseCode());
        return JsonParser.parseString(read(connection.getInputStream())).getAsJsonObject();
    }

    private JsonObject getJson(String url, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        assertEquals(200, connection.getResponseCode());
        return JsonParser.parseString(read(connection.getInputStream())).getAsJsonObject();
    }

    private static void write(HttpURLConnection connection, String body) throws Exception {
        OutputStream out = connection.getOutputStream();
        out.write(body.getBytes("UTF-8"));
        out.close();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return buffer.toString("UTF-8");
    }
}
