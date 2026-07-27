package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;

/**
 * The per-session research-control MCP endpoint. Its identity is bound to the session key AND the plugin
 * generation id (endpoint id {@code research.<sessionKey>.g<generationId>}) plus the registry's opaque token,
 * so after a plugin refresh an old generation's endpoint/token can never accept calls. On every state change
 * the tool set is recomputed by {@link ResearchToolPolicy} and pushed via {@code updateTools} (which emits
 * tools/list_changed). {@link #close()} unregisters the endpoint and is idempotent.
 */
public final class ResearchControlEndpoint {

    private final McpServerRegistry registry;
    private final ResearchControlContext context;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;

    public ResearchControlEndpoint(McpServerRegistry registry, String sessionKey, long pluginGenerationId,
                                   ResearchControlContext context) {
        this.registry = registry;
        this.context = context;
        this.endpointId = "research." + sessionKey + ".g" + pluginGenerationId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    /** @return the registry handle (endpoint id + opaque token), or null before open()/after close(). */
    public McpEndpointHandle getHandle() {
        return handle;
    }

    /** Register the endpoint and publish the initial state-derived tool set. Idempotent. */
    public void open() {
        if (closed || handle != null) {
            return;
        }
        handle = registry.registerEndpoint(new McpEndpointDefinition(endpointId, "Research Control"));
        refreshTools();
    }

    /** Recompute the tool set from the CURRENT state and push it (tools/list_changed). */
    public void refreshTools() {
        if (closed || handle == null) {
            return;
        }
        registry.updateTools(handle, ResearchToolPolicy.toolsFor(
                context.currentPhaseId(), context.currentStateId(), context));
    }

    /** Unregister the endpoint (invalidates the token). Idempotent. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (handle != null) {
            registry.unregisterEndpoint(handle);
            handle = null;
        }
    }
}
