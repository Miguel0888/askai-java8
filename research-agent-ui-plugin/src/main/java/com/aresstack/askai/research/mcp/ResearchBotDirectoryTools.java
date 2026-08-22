package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.List;

/**
 * The PUBLIC connector's tool catalog: the same three driving tools, but over the whole
 * {@link ResearchBotSessionDirectory} plus {@code sessions_list}. Every driving tool takes an OPTIONAL
 * {@code sessionId}; without one the call addresses the research session of the chat that is currently
 * SELECTED in the UI, which keeps every existing single-session call working unchanged.
 * <p>
 * Addressing a session is explicitly NOT the same as selecting it: driving session B never switches the
 * user's visible chat away from A. The catalog is STABLE — with no live session at all the four tools still
 * exist ({@code sessions_list} simply returns an empty list), so a connected client never sees the tool
 * surface appear and disappear.
 */
public final class ResearchBotDirectoryTools {

    private ResearchBotDirectoryTools() {
    }

    /** The usage guide handed to public clients (tools/list descriptions). */
    public static final String USAGE =
            "AskAI Research control across ALL running research sessions. Workflow: 1) call sessions_list "
            + "to see every live session with its sessionId (a stable UUID), title and state — titles are "
            + "NOT unique, the sessionId is the identity. 2) call session_state/chat_history/run_command "
            + "with that sessionId; omitting sessionId addresses the session of the chat currently selected "
            + "in the AskAI window. Driving another session does NOT switch the user's visible chat.";

    public static List<McpToolContribution> of(ResearchBotSessionDirectory directory) {
        return Arrays.asList(sessionsListTool(directory), runCommandTool(directory),
                sessionStateTool(directory), chatHistoryTool(directory));
    }

    private static final String SESSION_ID_PARAMETER =
            "The target session's UUID from sessions_list; omit to address the chat currently selected in "
            + "the AskAI window";

    /** Every live session with its stable id, its title and its own state line. */
    private static McpToolContribution sessionsListTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("sessions_list",
                "Every research session that is currently running, as JSON: sessionId (the stable UUID to "
                        + "pass to the other tools), title (display only - NOT unique), current (true for "
                        + "the chat selected in the UI) and state (the same text session_state returns). "
                        + "Call this FIRST when you want to work with a specific session.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String currentChat = directory.currentChatSessionId();
                        JsonArray entries = new JsonArray();
                        for (ResearchBotSessionRegistration registration : directory.list()) {
                            JsonObject entry = new JsonObject();
                            entry.addProperty("sessionId", registration.getPublicSessionId());
                            entry.addProperty("title", directory.titleOf(registration.getPublicSessionId()));
                            entry.addProperty("current",
                                    registration.getPublicSessionId().equals(currentChat));
                            // ONE source of truth for the state text: the session's own describeState().
                            String state = registration.getGateway().describeState();
                            entry.addProperty("state", state == null ? "" : state);
                            entries.add(entry);
                        }
                        JsonObject result = new JsonObject();
                        result.add("sessions", entries);
                        return McpToolResult.ok(result.toString());
                    }
                });
    }

    private static McpToolContribution runCommandTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("run_command",
                ResearchBotSessionTools.RUN_COMMAND_DESCRIPTION
                        + " Pass sessionId to drive a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String result = resolution.gateway.execute(
                                call.getString("command"), call.getString("arguments"));
                        if (result == null) {
                            return McpToolResult.error("This research session cannot accept commands "
                                    + "right now (it is starting up or already closing).");
                        }
                        return result.startsWith("rejected")
                                ? McpToolResult.error(result) : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER),
                McpToolParameter.string("command", false,
                        "The command name (see session_state); omit for a plain chat message"),
                McpToolParameter.string("arguments", false,
                        "The command arguments, or the chat message when no command is given"));
    }

    private static McpToolContribution sessionStateTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("session_state",
                ResearchBotSessionTools.SESSION_STATE_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String state = resolution.gateway.describeState();
                        return state == null
                                ? McpToolResult.error("This research session cannot report its state "
                                        + "right now (it is starting up or already closing).")
                                : McpToolResult.ok(state);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER));
    }

    private static McpToolContribution chatHistoryTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("chat_history",
                ResearchBotSessionTools.CHAT_HISTORY_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String history = resolution.gateway.describeHistory(call.getBoolean("raw", false));
                        return history == null
                                ? McpToolResult.error("This research session cannot report its history "
                                        + "right now (it is starting up or already closing).")
                                : McpToolResult.ok(history);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER),
                McpToolParameter.string("raw", false,
                        "true = every recorded message of every phase (default: phase summaries)"));
    }

    /** Either a gateway or the concrete reason no session could be addressed — never both. */
    private static final class Resolution {
        private final ResearchBotSessionGateway gateway;
        private final String problem;

        private Resolution(ResearchBotSessionGateway gateway, String problem) {
            this.gateway = gateway;
            this.problem = problem;
        }
    }

    /**
     * THE one resolution rule for all three driving tools: an explicit {@code sessionId} addresses exactly
     * that session (a stale/unknown id is an error, never a silent redirect), an omitted one addresses the
     * chat selected in the UI.
     */
    private static Resolution resolve(ResearchBotSessionDirectory directory, McpToolCall call) {
        String requested = call.getString("sessionId");
        if (requested != null && !requested.trim().isEmpty()) {
            ResearchBotSessionRegistration registration = directory.find(requested);
            return registration != null
                    ? new Resolution(registration.getGateway(), null)
                    : new Resolution(null, "No live research session with sessionId '" + requested.trim()
                            + "'. It may have been closed. Call sessions_list for the current ids.");
        }
        ResearchBotSessionRegistration current = directory.currentSession();
        if (current != null) {
            return new Resolution(current.getGateway(), null);
        }
        return new Resolution(null, directory.list().isEmpty()
                ? "No research session is running in AskAI right now."
                : "The chat currently selected in AskAI runs no research session (or several sessions are "
                        + "live and none is selected). Call sessions_list and pass sessionId explicitly.");
    }
}
