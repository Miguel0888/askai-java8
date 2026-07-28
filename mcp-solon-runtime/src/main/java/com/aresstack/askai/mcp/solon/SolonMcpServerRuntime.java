package com.aresstack.askai.mcp.solon;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolHandler;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real Solon streamable-HTTP MCP server behind the generic {@link McpServerRegistry} port. It binds a single
 * Solon app to {@code 127.0.0.1} on a free ephemeral port and exposes one streamable MCP endpoint per
 * registered logical endpoint. The unguessable session token is embedded in the endpoint path, so a wrong
 * token resolves to no route (loopback + per-endpoint token). Tool sets are updated dynamically; unregister
 * stops the endpoint; shutdown stops all endpoints and the Solon app (idempotent). No Solon type is exposed
 * on the port — callers get an {@link McpEndpointHandle} plus {@link #endpointUrl(McpEndpointHandle)}.
 */
public final class SolonMcpServerRuntime implements McpServerRegistry {

    private static final SecureRandom RANDOM = new SecureRandom();

    // Solon.start/stopBlock are PROCESS-GLOBAL. Booting the app is therefore a JVM-wide one-time act shared
    // by every runtime instance: a stop-then-restart of Solon within one JVM is not reliable (observed as
    // order-dependent failures when several tests each created their own runtime). Instances keep their own
    // registrations; shutdown() removes only those — the Solon app itself lives until the JVM exits.
    private static final Object BOOT_LOCK = new Object();
    private static volatile int sharedPort = -1;

    private final Map<String, Registration> byId = new ConcurrentHashMap<String, Registration>();
    private volatile boolean shutdown;

    /** Boot the shared Solon app on 127.0.0.1:&lt;free port&gt; (JVM-wide once). Idempotent. */
    public void start() {
        if (shutdown) {
            return;
        }
        synchronized (BOOT_LOCK) {
            if (sharedPort < 0) {
                int port = freeLoopbackPort();
                Solon.start(SolonMcpServerRuntime.class, new String[]{
                        "--server.host=127.0.0.1",
                        "--server.port=" + port
                });
                sharedPort = port;
            }
        }
    }

    public int getPort() {
        return sharedPort;
    }

    /** The full loopback URL a client uses for this endpoint (path carries the session token). */
    public String endpointUrl(McpEndpointHandle handle) {
        Registration reg = byId.get(handle == null ? null : handle.getEndpointId());
        if (reg == null || !reg.token.equals(handle.getToken())) {
            return null;
        }
        return "http://127.0.0.1:" + sharedPort + reg.path;
    }

    @Override
    public synchronized McpEndpointHandle registerEndpoint(McpEndpointDefinition definition) {
        if (shutdown) {
            throw new IllegalStateException("runtime is shut down");
        }
        start();
        String token = newToken();
        String path = "/mcp/" + definition.getEndpointId() + "/" + token;
        McpServerEndpointProvider provider = McpServerEndpointProvider.builder()
                .name(definition.getEndpointId())
                .version("1.0")
                .channel("streamable")
                .mcpEndpoint(path)
                .build();
        provider.postStart(); // attach the streamable route to the running Solon app
        byId.put(definition.getEndpointId(), new Registration(provider, token, path));
        return new McpEndpointHandle(definition.getEndpointId(), token);
    }

    @Override
    public void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools) {
        Registration reg = authorized(handle);
        if (reg == null) {
            return;
        }
        synchronized (reg) {
            // Remove the previous tool set, then add the new one (the provider emits tools/list_changed).
            List<String> existing = new ArrayList<String>();
            for (FunctionTool t : reg.provider.getTools()) {
                existing.add(t.name());
            }
            for (String name : existing) {
                reg.provider.removeTool(name);
            }
            if (tools != null) {
                for (McpToolContribution tool : tools) {
                    reg.provider.addTool(toFunctionTool(tool));
                }
            }
        }
    }

    @Override
    public void unregisterEndpoint(McpEndpointHandle handle) {
        Registration reg = authorized(handle);
        if (reg == null) {
            return;
        }
        byId.remove(handle.getEndpointId());
        try {
            reg.provider.stop();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Idempotent shutdown: stop every endpoint, then the Solon app. */
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        for (Registration reg : byId.values()) {
            try {
                reg.provider.stop();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        byId.clear();
        // The shared Solon app is deliberately NOT stopped: Solon is process-global and a stop-then-
        // restart within one JVM is unreliable. Without registrations it serves no route (all tokens
        // invalidated); it ends with the JVM.
    }

    // ------------------------------------------------------------------ helpers

    private Registration authorized(McpEndpointHandle handle) {
        if (shutdown || handle == null) {
            return null;
        }
        Registration reg = byId.get(handle.getEndpointId());
        return reg != null && reg.token.equals(handle.getToken()) ? reg : null;
    }

    private static FunctionTool toFunctionTool(final McpToolContribution tool) {
        FunctionToolDesc desc = new FunctionToolDesc(tool.getName()).description(tool.getDescription());
        for (McpToolParameter p : tool.getParameters()) {
            switch (p.getType()) {
                case INTEGER:
                    desc.intParamAdd(p.getName(), p.getDescription());
                    break;
                case BOOLEAN:
                    desc.boolParamAdd(p.getName(), p.getDescription());
                    break;
                case STRING:
                case ENUM:
                default:
                    desc.stringParamAdd(p.getName(), p.getDescription());
                    break;
            }
        }
        desc.doHandle(new ToolHandler() {
            public Object handle(Map<String, Object> args) throws Throwable {
                McpToolResult result = tool.getHandler().invoke(new McpToolCall(tool.getName(), args));
                if (result.isError()) {
                    throw new IllegalStateException(result.getText());
                }
                return result.getText();
            }
        });
        return desc;
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static int freeLoopbackPort() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("could not allocate a loopback port", ex);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static final class Registration {
        private final McpServerEndpointProvider provider;
        private final String token;
        private final String path;

        private Registration(McpServerEndpointProvider provider, String token, String path) {
            this.provider = provider;
            this.token = token;
            this.path = path;
        }
    }
}
