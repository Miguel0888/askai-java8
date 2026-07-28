package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.solon.SolonAcpAgentConnector;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClientFactory;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.mcp.solon.SolonMcpToolClientFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app-level owner of the host runtime services handed to agent plugins via
 * {@code AgentHostContext.getService}: the Solon MCP server runtime (LAZY — the loopback HTTP server only
 * starts when a plugin actually registers an endpoint, i.e. when the productive research mode is used), the
 * MCP tool-client factory and the ACP agent connector. One instance per application; {@link #shutdown()}
 * stops the MCP runtime if it was ever started.
 */
public final class AgentRuntimeServices {

    private final LazyRegistry registry = new LazyRegistry();
    private final McpToolClientFactory toolClients = new SolonMcpToolClientFactory();
    // The agent's STDERR goes to the app console: without it a failed agent start is undiagnosable.
    private final AcpAgentConnector connector = new SolonAcpAgentConnector(Duration.ofSeconds(180),
            new java.util.function.Consumer<String>() {
                public void accept(String line) {
                    System.err.println("[research-agent] " + line);
                }
            });

    /** The service map for DefaultAgentHostContext (neutral interface types as keys). */
    public Map<Class<?>, Object> asServiceMap() {
        Map<Class<?>, Object> services = new LinkedHashMap<Class<?>, Object>();
        services.put(McpServerRegistry.class, registry);
        services.put(McpToolClientFactory.class, toolClients);
        services.put(AcpAgentConnector.class, connector);
        return services;
    }

    public void shutdown() {
        registry.shutdown();
    }

    /** Starts the real Solon MCP runtime on first use; thread-safe; idempotent shutdown. */
    private static final class LazyRegistry implements McpServerRegistry {
        private volatile SolonMcpServerRuntime runtime;

        private SolonMcpServerRuntime runtime() {
            SolonMcpServerRuntime current = runtime;
            if (current == null) {
                synchronized (this) {
                    if (runtime == null) {
                        runtime = new SolonMcpServerRuntime();
                    }
                    current = runtime;
                }
            }
            return current;
        }

        public McpEndpointHandle registerEndpoint(McpEndpointDefinition definition) {
            return runtime().registerEndpoint(definition);
        }

        public void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools) {
            runtime().updateTools(handle, tools);
        }

        public void unregisterEndpoint(McpEndpointHandle handle) {
            runtime().unregisterEndpoint(handle);
        }

        public String endpointUrl(McpEndpointHandle handle) {
            return runtime().endpointUrl(handle);
        }

        void shutdown() {
            SolonMcpServerRuntime current = runtime;
            if (current != null) {
                current.shutdown();
            }
        }
    }
}
