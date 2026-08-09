package com.aresstack.askai.research.connector;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The public ChatGPT-connector face of AskAI: ONE plain-HTTP listener (TLS terminates at the Apache
 * reverse proxy on the gateway machine) serving the OAuth endpoints, the RFC discovery metadata and the
 * MCP JSON-RPC endpoint {@code /askai} (initialize, tools/list, tools/call — protocol 2025-03-26, the
 * same hybrid GET-SSE/POST-JSON contract the proven Pyloros connector speaks). The tools are resolved at
 * CALL TIME from a {@link ToolProvider}, so the connector survives research-session changes.
 */
public final class ChatGptConnectorServer {

    /** Resolves the CURRENT driving tools; empty when no research session is attached right now. */
    public interface ToolProvider {
        List<McpToolContribution> tools();
    }

    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";
    private static final Gson GSON = new Gson();

    private final ConnectorConfig config;
    private final ConnectorOAuthService oauth;
    private final ToolProvider toolProvider;
    private HttpServer server;

    public ChatGptConnectorServer(ConnectorConfig config, ConnectorOAuthService oauth,
                                  ToolProvider toolProvider) {
        this.config = config;
        this.oauth = oauth;
        this.toolProvider = toolProvider;
    }

    /** Bind on all interfaces (the Apache proxy machine must reach this port). */
    public synchronized void start() throws Exception {
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(config.getPort()), 0);
        created.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) {
                route(exchange);
            }
        });
        created.setExecutor(Executors.newFixedThreadPool(4));
        created.start();
        server = created;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** The actually bound local port (== configured port unless 0 was configured for tests). */
    public synchronized int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void route(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/".equals(path)) {
                status(exchange);
            } else if ("GET".equals(method) && isAuthorizationServerMetadataPath(path)) {
                authorizationServerMetadata(exchange);
            } else if ("GET".equals(method) && path.startsWith("/.well-known/oauth-protected-resource")) {
                protectedResourceMetadata(exchange);
            } else if ("GET".equals(method) && "/oauth/authorize".equals(path)) {
                authorize(exchange);
            } else if ("POST".equals(method) && "/oauth/token".equals(path)) {
                token(exchange);
            } else if ("GET".equals(method) && "/health".equals(path)) {
                sendJson(exchange, 200, singleton("status", "ok"));
            } else if (ConnectorConfig.MCP_PUBLIC_PATH.equals(path)) {
                if ("GET".equals(method)) {
                    mcpSse(exchange);
                } else if ("POST".equals(method)) {
                    mcpPost(exchange);
                } else {
                    sendJson(exchange, 405, singleton("error", "method_not_allowed"));
                }
            } else {
                sendJson(exchange, 404, singleton("error", "not_found"));
            }
        } catch (Exception unexpected) {
            try {
                sendJson(exchange, 500, singleton("error", "internal_error"));
            } catch (Exception ignored) {
                // the socket is gone; nothing left to report
            }
        } finally {
            exchange.close();
        }
    }

    private static boolean isAuthorizationServerMetadataPath(String path) {
        return path.startsWith("/.well-known/oauth-authorization-server")
                || path.startsWith("/.well-known/openid-configuration")
                || path.equals(ConnectorConfig.MCP_PUBLIC_PATH + "/.well-known/oauth-authorization-server")
                || path.equals(ConnectorConfig.MCP_PUBLIC_PATH + "/.well-known/openid-configuration");
    }

    private void status(HttpExchange exchange) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("status", "ok");
        body.addProperty("name", "askai");
        body.addProperty("mcp", config.getPublicOrigin() + ConnectorConfig.MCP_PUBLIC_PATH);
        body.addProperty("authorization_endpoint", config.getPublicOrigin() + "/oauth/authorize");
        body.addProperty("token_endpoint", config.getPublicOrigin() + "/oauth/token");
        body.addProperty("advertised_pkce_method", "S256");
        sendJson(exchange, 200, body);
    }

    private void authorizationServerMetadata(HttpExchange exchange) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("issuer", config.getPublicOrigin());
        body.addProperty("authorization_endpoint", config.getPublicOrigin() + "/oauth/authorize");
        body.addProperty("token_endpoint", config.getPublicOrigin() + "/oauth/token");
        body.add("response_types_supported", array("code"));
        body.add("grant_types_supported", array("authorization_code", "refresh_token"));
        body.add("token_endpoint_auth_methods_supported", array("client_secret_basic", "client_secret_post"));
        body.add("code_challenge_methods_supported", array("S256"));
        body.add("scopes_supported", array("mcp"));
        sendJson(exchange, 200, body);
    }

    private void protectedResourceMetadata(HttpExchange exchange) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("resource", config.getPublicOrigin() + ConnectorConfig.MCP_PUBLIC_PATH);
        body.add("authorization_servers", array(config.getPublicOrigin()));
        body.add("scopes_supported", array("mcp"));
        body.add("bearer_methods_supported", array("header"));
        sendJson(exchange, 200, body);
    }

    private void authorize(HttpExchange exchange) throws Exception {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        try {
            String location = oauth.authorize(query.get("response_type"), query.get("client_id"),
                    query.get("redirect_uri"), query.get("state"),
                    query.containsKey("scope") ? query.get("scope") : "mcp",
                    query.get("code_challenge"), query.get("code_challenge_method"));
            exchange.getResponseHeaders().set("Location", location);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(302, -1);
        } catch (OAuthError error) {
            sendOAuthError(exchange, error);
        }
    }

    private void token(HttpExchange exchange) throws Exception {
        Map<String, String> form = parseForm(readBody(exchange));
        String clientId = form.get("client_id");
        String clientSecret = form.get("client_secret");
        String basic = exchange.getRequestHeaders().getFirst("Authorization");
        if (basic != null && basic.startsWith("Basic ")) {
            String decoded = new String(Base64.getDecoder().decode(basic.substring("Basic ".length()).trim()),
                    "UTF-8");
            int separator = decoded.indexOf(':');
            if (separator >= 0) {
                clientId = decoded.substring(0, separator);
                clientSecret = decoded.substring(separator + 1);
            }
        }
        try {
            Map<String, Object> body = oauth.token(clientId, clientSecret, form.get("grant_type"),
                    form.get("code"), form.get("refresh_token"), form.get("redirect_uri"),
                    form.get("code_verifier"));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            sendJson(exchange, 200, GSON.toJsonTree(body).getAsJsonObject());
        } catch (OAuthError error) {
            sendOAuthError(exchange, error);
        }
    }

    private void mcpSse(HttpExchange exchange) throws Exception {
        if (!bearerOk(exchange)) {
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        byte[] body = ("event: endpoint\ndata: " + ConnectorConfig.MCP_PUBLIC_PATH + "\n\n")
                .getBytes("UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    private void mcpPost(HttpExchange exchange) throws Exception {
        if (!bearerOk(exchange)) {
            return;
        }
        JsonObject request;
        try {
            request = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
        } catch (RuntimeException invalid) {
            sendJson(exchange, 400, singleton("error", "Invalid JSON"));
            return;
        }
        JsonElement id = request.get("id");
        String rpcMethod = request.has("method") && !request.get("method").isJsonNull()
                ? request.get("method").getAsString() : "";
        if (id == null || id.isJsonNull()) {
            sendJson(exchange, 202, singleton("status", "accepted")); // notification
            return;
        }
        if ("initialize".equals(rpcMethod)) {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
            JsonObject capabilities = new JsonObject();
            capabilities.add("tools", new JsonObject());
            capabilities.add("resources", new JsonObject());
            capabilities.add("prompts", new JsonObject());
            result.add("capabilities", capabilities);
            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("name", "askai");
            serverInfo.addProperty("version", "1.0");
            result.add("serverInfo", serverInfo);
            rpcResult(exchange, id, result);
        } else if ("tools/list".equals(rpcMethod)) {
            JsonObject result = new JsonObject();
            result.add("tools", toolCatalog());
            rpcResult(exchange, id, result);
        } else if ("resources/list".equals(rpcMethod)) {
            JsonObject result = new JsonObject();
            result.add("resources", new JsonArray());
            rpcResult(exchange, id, result);
        } else if ("prompts/list".equals(rpcMethod)) {
            JsonObject result = new JsonObject();
            result.add("prompts", new JsonArray());
            rpcResult(exchange, id, result);
        } else if ("tools/call".equals(rpcMethod) || "call_tool".equals(rpcMethod)) {
            callTool(exchange, id, request);
        } else {
            rpcError(exchange, id, -32601, "Method not supported");
        }
    }

    private JsonArray toolCatalog() {
        JsonArray tools = new JsonArray();
        for (McpToolContribution tool : toolProvider.tools()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", tool.getName());
            item.addProperty("description", tool.getDescription());
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (McpToolParameter parameter : tool.getParameters()) {
                JsonObject property = new JsonObject();
                property.addProperty("type", jsonType(parameter));
                property.addProperty("description", parameter.getDescription());
                properties.add(parameter.getName(), property);
                if (parameter.isRequired()) {
                    required.add(parameter.getName());
                }
            }
            schema.add("properties", properties);
            schema.add("required", required);
            item.add("inputSchema", schema);
            tools.add(item);
        }
        return tools;
    }

    private static String jsonType(McpToolParameter parameter) {
        String type = parameter.getType() == null ? "STRING" : parameter.getType().name();
        if ("INTEGER".equals(type)) {
            return "integer";
        }
        if ("BOOLEAN".equals(type)) {
            return "boolean";
        }
        return "string";
    }

    private void callTool(HttpExchange exchange, JsonElement id, JsonObject request) throws Exception {
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") && !params.get("name").isJsonNull()
                ? params.get("name").getAsString() : "";
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            for (Map.Entry<String, JsonElement> argument
                    : params.getAsJsonObject("arguments").entrySet()) {
                JsonElement value = argument.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    arguments.put(argument.getKey(), Boolean.valueOf(value.getAsBoolean()));
                } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    arguments.put(argument.getKey(), Long.valueOf(value.getAsLong()));
                } else if (!value.isJsonNull()) {
                    arguments.put(argument.getKey(), value.isJsonPrimitive()
                            ? value.getAsString() : value.toString());
                }
            }
        }
        McpToolContribution tool = findTool(name);
        if (tool == null) {
            rpcError(exchange, id, -32602, "Unknown tool: " + name);
            return;
        }
        McpToolResult result;
        try {
            result = tool.getHandler().invoke(new McpToolCall(name, arguments));
        } catch (RuntimeException failure) {
            rpcError(exchange, id, -32000, failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            return;
        }
        JsonObject payload = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result.getText() == null ? "" : result.getText());
        content.add(text);
        payload.add("content", content);
        payload.addProperty("isError", result.isError());
        rpcResult(exchange, id, payload);
    }

    private McpToolContribution findTool(String name) {
        for (McpToolContribution tool : toolProvider.tools()) {
            if (tool.getName().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private boolean bearerOk(HttpExchange exchange) throws Exception {
        if (oauth.isBearerAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        exchange.getResponseHeaders().set("WWW-Authenticate",
                "Bearer error=\"invalid_token\", error_description=\"The access token is invalid or expired\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        sendJson(exchange, 401, singleton("error", "invalid_token"));
        return false;
    }

    private void rpcResult(HttpExchange exchange, JsonElement id, JsonObject result) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.add("id", id);
        body.add("result", result);
        sendJson(exchange, 200, body);
    }

    private void rpcError(HttpExchange exchange, JsonElement id, int code, String message)
            throws Exception {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "" : message);
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.add("id", id);
        body.add("error", error);
        sendJson(exchange, 200, body);
    }

    private void sendOAuthError(HttpExchange exchange, OAuthError error) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("error", error.getError());
        if (error.getErrorDescription() != null) {
            body.addProperty("error_description", error.getErrorDescription());
        }
        sendJson(exchange, error.getStatusCode(), body);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body) throws Exception {
        byte[] bytes = GSON.toJson(body).getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static JsonObject singleton(String key, String value) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        return body;
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String readBody(HttpExchange exchange) throws Exception {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static Map<String, String> parseForm(String encoded) throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                values.put(URLDecoder.decode(pair.substring(0, separator), "UTF-8"),
                        URLDecoder.decode(pair.substring(separator + 1), "UTF-8"));
            }
        }
        return values;
    }
}
