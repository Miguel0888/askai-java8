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
 * The per-session BOT-CONTROL MCP endpoint: exactly the tools an external driver (a bot, a headless gate, a
 * script) needs — {@code run_command}, {@code session_state} and {@code chat_history}. It is a THIN access
 * layer over the session's central command processor; no action logic lives here. Deliberately SEPARATE from
 * both the agent-facing {@link ResearchControlEndpoint} (the TeamAgent never gets these tools) and the
 * runtime-plumbing {@link ResearchServiceEndpoint} (manual_source_* is internal runtime→host traffic, not a
 * bot API). {@code <projectDir>/service-endpoint.json} hands THIS endpoint to external clients.
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

    /**
     * THE session gateway: structured command execution, the structured session state, and the
     * phase-attributed chat history. Implemented by the session, resolved at call time; {@code null}
     * results mean "no session attached".
     */
    public interface SessionGateway {
        /** Execute one command with arguments; empty command = the arguments are a plain chat message. */
        String execute(String command, String arguments);

        /** Phase/run state + currently valid commands, clickable buttons and search suggestions. */
        String describeState();

        /** The phase-attributed chat record; {@code raw} = every entry instead of phase summaries. */
        String describeHistory(boolean raw);
    }

    private final McpServerRegistry registry;
    private final SessionGateway gateway;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;

    public ResearchBotControlEndpoint(McpServerRegistry registry, String sessionKey,
                                      long pluginGenerationId, SessionGateway gateway) {
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
        registry.updateTools(handle, drivingTools(gateway));
    }

    /**
     * The fixed THREE-tool set over one gateway — shared by this registry endpoint and the public
     * ChatGPT connector, so both faces always offer the identical contract.
     */
    public static java.util.List<McpToolContribution> drivingTools(SessionGateway gateway) {
        return Arrays.asList(
                runCommandTool(gateway), sessionStateTool(gateway), chatHistoryTool(gateway));
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

    /**
     * THE generic driving tool: one structured command + arguments. No command = the arguments are a plain
     * chat message. Unknown / currently-not-allowed commands come back as typed rejections.
     */
    private static McpToolContribution runCommandTool(final SessionGateway gateway) {
        return McpToolContribution.of("run_command",
                "Execute one research command. Always-on commands: search <query> (web search), "
                        + "generate-visualization, generate-outline, review-sources. State commands "
                        + "(only when the phase allows them): submit-scope, approve, request-changes, "
                        + "continue, retry, resume, pause, cancel. Omit 'command' to send the arguments "
                        + "as a plain CHAT MESSAGE to the research agent. Unknown/not-allowed commands "
                        + "are rejected with the currently valid list. Call session_state first.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String result = gateway == null ? null
                                : gateway.execute(call.getString("command"), call.getString("arguments"));
                        if (result == null) {
                            return McpToolResult.error(
                                    "No research session is attached to this endpoint yet.");
                        }
                        return result.startsWith("rejected")
                                ? McpToolResult.error(result) : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("command", false,
                        "The command name (see session_state); omit for a plain chat message"),
                McpToolParameter.string("arguments", false,
                        "The command arguments, or the chat message when no command is given"));
    }

    /** The structured session state: phase/run state first, then valid commands, buttons, suggestions. */
    private static McpToolContribution sessionStateTool(final SessionGateway gateway) {
        return McpToolContribution.of("session_state",
                "Current research phase and run state, plus the currently valid commands (the same set the "
                        + "UI buttons offer), the clickable decision buttons and the search suggestions "
                        + "(each directly executable via run_command).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String state = gateway == null ? null : gateway.describeState();
                        return state == null
                                ? McpToolResult.error(
                                        "No research session is attached to this endpoint yet.")
                                : McpToolResult.ok(state);
                    }
                });
    }

    /** The phase-attributed chat record: summaries per finished phase by default, raw=true for everything. */
    private static McpToolContribution chatHistoryTool(final SessionGateway gateway) {
        return McpToolContribution.of("chat_history",
                "The chat record of this session, attributed to research phases: finished phases as ONE "
                        + "summary (outcome + message count), the current phase in detail. Pass raw=true "
                        + "for every recorded message of every phase.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        boolean raw = call.getBoolean("raw", false);
                        String history = gateway == null ? null : gateway.describeHistory(raw);
                        return history == null
                                ? McpToolResult.error(
                                        "No research session is attached to this endpoint yet.")
                                : McpToolResult.ok(history);
                    }
                },
                McpToolParameter.string("raw", false,
                        "true = every recorded message of every phase (default: phase summaries)"));
    }
}
