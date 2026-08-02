package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.util.Collections;

/**
 * The per-session INTERNAL research-service MCP endpoint — a SEPARATE namespace from the agent-facing
 * {@link ResearchControlEndpoint}. It hosts runtime→host SERVICE operations a USER-triggered backend service
 * needs (currently {@code manual_source_accept}) which must NEVER be offered to the agent/model. Because they
 * live on their OWN endpoint, they are structurally absent from the agent tool catalog (and from any later
 * capability filter, S4) — no "hidden tool" flag in the shared MCP registry is required.
 *
 * <p>{@code manual_source_accept} delegates to the EXACT SAME host-side acceptance service the phase-gated
 * agent tool {@code source_accept} uses ({@link ResearchControlContext#acceptCapture}) — acceptance logic is
 * never duplicated. The ONLY difference is the trust boundary: this endpoint is not phase-gated, and the
 * trusted user origin is established by WHICH endpoint was invoked, never by a tool argument an agent could
 * forge. {@link #close()} unregisters the endpoint and is idempotent.</p>
 */
public final class ResearchServiceEndpoint {

    /** The single internal tool name — deliberately distinct from the agent's phase-gated {@code source_accept}. */
    public static final String MANUAL_SOURCE_ACCEPT = "manual_source_accept";

    private final McpServerRegistry registry;
    private final ResearchControlContext context;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;

    public ResearchServiceEndpoint(McpServerRegistry registry, String sessionKey, long pluginGenerationId,
                                   ResearchControlContext context) {
        this.registry = registry;
        this.context = context;
        this.endpointId = "research-service." + sessionKey + ".g" + pluginGenerationId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    /** @return the registry handle (endpoint id + opaque token), or null before open()/after close(). */
    public McpEndpointHandle getHandle() {
        return handle;
    }

    /** Register the internal endpoint and publish its fixed tool set (no phase dependency). Idempotent. */
    public void open() {
        if (closed || handle != null) {
            return;
        }
        handle = registry.registerEndpoint(
                new McpEndpointDefinition(endpointId, "Research Service (internal)"));
        registry.updateTools(handle, Collections.singletonList(manualSourceAcceptTool(context)));
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

    private static McpToolContribution manualSourceAcceptTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(MANUAL_SOURCE_ACCEPT,
                "Internal: accept a visited capture as a research source for a USER-triggered search "
                        + "(not phase-gated, never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String captureId = call.getString("capture_id");
                        if (captureId == null || captureId.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: capture_id");
                        }
                        // Delegate to the SAME host-side SourceAcceptanceService the agent path uses; no phase
                        // gate — the trusted user origin is this internal endpoint itself. The user query that
                        // found the source is persisted with it (so "already searched" survives a restart).
                        String searchQuery = call.getString("search_query");
                        String result = ctx.acceptCapture(captureId.trim(),
                                searchQuery == null ? "" : searchQuery);
                        return result == null
                                ? McpToolResult.error("Unknown capture: " + captureId)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("capture_id", true, "The capture id from a visited page"),
                McpToolParameter.string("search_query", false,
                        "The user web-search query that found this capture"));
    }
}
