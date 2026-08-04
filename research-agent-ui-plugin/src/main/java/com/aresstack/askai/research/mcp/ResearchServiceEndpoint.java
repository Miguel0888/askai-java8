package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.util.Arrays;

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

    /** The internal tool names — deliberately distinct from the agent's phase-gated {@code source_accept}. */
    public static final String MANUAL_SOURCE_ACCEPT = "manual_source_accept";
    public static final String MANUAL_SOURCE_PARK = "manual_source_park";

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
        registry.updateTools(handle,
                Arrays.asList(manualSourceAcceptTool(context), manualSourceParkTool(context)));
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
                        boolean userRelevant = call.getBoolean("user_relevant", false);
                        String language = call.getString("language");
                        String result = ctx.acceptCapture(captureId.trim(),
                                searchQuery == null ? "" : searchQuery, userRelevant,
                                language == null ? "" : language);
                        return result == null
                                ? McpToolResult.error("Unknown capture: " + captureId)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("capture_id", true, "The capture id from a visited page"),
                McpToolParameter.string("search_query", false,
                        "The user web-search query that found this capture"),
                McpToolParameter.string("user_relevant", false,
                        "true when the user marked this page relevant in the HUD (⭐)"),
                McpToolParameter.string("language", false,
                        "the language snapshot of the search that found this capture (en/de)"));
    }

    private static McpToolContribution manualSourceParkTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(MANUAL_SOURCE_PARK,
                "Internal: park a reranked search candidate as a scored source before it is visited "
                        + "(empty full text; not phase-gated, never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String url = call.getString("url");
                        if (url == null || url.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: url");
                        }
                        String searchQuery = call.getString("search_query");
                        String result = ctx.parkCandidate(url.trim(),
                                nullToEmpty(call.getString("title")),
                                nullToEmpty(call.getString("excerpt")),
                                parseScore(call.getString("score")),
                                searchQuery == null ? "" : searchQuery);
                        return result == null ? McpToolResult.error("Could not park: " + url)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("url", true, "The candidate's resolved target URL"),
                McpToolParameter.string("title", false, "The candidate title"),
                McpToolParameter.string("excerpt", false, "The search-result snippet/excerpt"),
                McpToolParameter.string("score", false, "The reranker relevance score (a double)"),
                McpToolParameter.string("search_query", false,
                        "The user web-search query that found this candidate"));
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /** Missing/empty/non-numeric score → NaN ("unknown"); a valid double otherwise. */
    private static double parseScore(String v) {
        if (v == null || v.trim().isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }
}
