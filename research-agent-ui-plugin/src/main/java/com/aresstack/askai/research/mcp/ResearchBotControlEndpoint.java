package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;

/**
 * The per-session BOT-CONTROL MCP endpoint: exactly the tools an external driver (a bot, a headless gate, a
 * script) needs — {@code run_command}, {@code session_state} and {@code chat_history}. It is a THIN access
 * layer over the session's central command processor; no action logic lives here. Deliberately SEPARATE from
 * both the agent-facing {@link ResearchControlEndpoint} (the TeamAgent never gets these tools) and the
 * runtime-plumbing {@link ResearchServiceEndpoint} (manual_source_* is internal runtime→host traffic, not a
 * bot API). {@code <projectDir>/service-endpoint.json} hands THIS endpoint to external clients.
 * <p>
 * This endpoint is bound to EXACTLY ONE session: its own token IS the session selection, so none of its
 * tools takes a session id. The app-wide public ChatGPT connector is a different face with a different
 * catalog ({@link ResearchBotDirectoryTools}) — the two are deliberately no longer the same tool set.
 */
public final class ResearchBotControlEndpoint {

    /**
     * The bot USAGE guide — ONE text, handed out wherever a bot looks first: in service-endpoint.json
     * ("usage") and in the tools/list descriptions. The MCP-standard initialize.instructions field would be
     * the native place, but the current Solon server API cannot set it; tools/list IS the standard
     * discovery, so every MCP client sees this contract without any custom endpoint.
     */
    public static final String USAGE =
            "AskAI Research bot control. Workflow: 1) call session_state to learn the current phase, the "
            + "currently valid commands, the clickable buttons and the search suggestions (each suggestion "
            + "is directly executable). 2) act via run_command: pass command + arguments "
            + "(e.g. command=search, arguments=<query>); omit 'command' to send arguments as a plain chat "
            + "message to the research agent. Unknown or currently-not-allowed commands are rejected with "
            + "the reason AND the valid command list. 3) read the conversation via chat_history "
            + "(phase summaries by default, raw=true for every message). Commands are state-dependent — "
            + "re-check session_state after every action.";

    private final McpServerRegistry registry;
    private final ResearchBotSessionGateway gateway;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;

    public ResearchBotControlEndpoint(McpServerRegistry registry, String sessionKey,
                                      long pluginGenerationId, ResearchBotSessionGateway gateway) {
        this.registry = registry;
        this.gateway = gateway;
        this.endpointId = "research-bot." + sessionKey + ".g" + pluginGenerationId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    /** @return the registry handle (endpoint id + opaque token), or null before open()/after close(). */
    public McpEndpointHandle getHandle() {
        return handle;
    }

    /** Register the endpoint and publish its fixed THREE-tool set. Idempotent. */
    public void open() {
        if (closed || handle != null) {
            return;
        }
        handle = registry.registerEndpoint(
                new McpEndpointDefinition(endpointId, "Research Bot Control"));
        registry.updateTools(handle, ResearchBotSessionTools.of(gateway));
    }

    /** The client-facing streamable-HTTP URL (token in the path), or {@code null} while not open. */
    public String connectionUrl() {
        return handle == null ? null : registry.endpointUrl(handle);
    }

    /** The CURRENT tool names, fetched live from the registry (never a hardcoded copy). */
    public java.util.List<String> toolNames() {
        return handle == null ? new java.util.ArrayList<String>() : registry.toolNames(handle);
    }

    /** The CURRENT tools with their descriptions, fetched live from the registry. */
    public java.util.Map<String, String> toolCatalog() {
        return handle == null ? new java.util.LinkedHashMap<String, String>()
                : registry.toolCatalog(handle);
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
