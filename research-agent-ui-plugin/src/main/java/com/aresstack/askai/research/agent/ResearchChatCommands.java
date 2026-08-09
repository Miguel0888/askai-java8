package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionContext;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor;
import com.aresstack.askai.plugin.api.agent.command.CommandArgumentDescriptor;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletion;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionRequest;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandInvocation;
import com.aresstack.askai.plugin.api.agent.command.CompletionKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The research agent's slash commands. They are user controls (not agent tools): each translates a parsed
 * invocation into a typed control call on {@link ResearchAgentSession}, so the state machine never sees a raw
 * string. This is a representative set for Commit 10; the full set arrives in Commit 12.
 */
public final class ResearchChatCommands {

    private ResearchChatCommands() {
    }

    public static List<ChatCommandContribution> all() {
        // Issue #33 cleanup: ONE synchronized command surface. /do covers every state-machine command the
        // buttons show (approve/pause/resume/cancel/... were redundant hardcoded twins and are gone);
        // /search is the user web search; /open reveals artifact tabs (a GUI-only concern).
        List<ChatCommandContribution> commands = new ArrayList<ChatCommandContribution>();
        commands.add(new OpenCommand());
        commands.add(new DoCommand());
        commands.add(new SearchCommand());
        return commands;
    }

    private static ResearchAgentSession research(AgentSessionContext context) {
        AgentSession session = context == null ? null : context.getSession();
        return session instanceof ResearchAgentSession ? (ResearchAgentSession) session : null;
    }

    /** Base with a no-op completion; subclasses override where arguments exist. */
    private abstract static class Base implements ChatCommandContribution {
        public CommandCompletionResult complete(CommandCompletionRequest request, AgentSessionContext context) {
            return CommandCompletionResult.empty();
        }
    }

    /**
     * {@code /do <command>} — the STRUCTURED phase-action surface. It completes exactly the commands the
     * live state machine allows right now (single source of truth; no phase rules in the UI) and dispatches
     * through the {@link com.aresstack.askai.research.backend.ResearchSessionCommandPort} — never as a
     * synthetic chat message. Rejections surface the structured status readably.
     */
    private static final class DoCommand extends Base {
        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of("do", "Trigger an allowed research phase action",
                    "/do <command>",
                    new CommandArgumentDescriptor("command", "One of the currently allowed commands", true));
        }

        @Override
        public CommandCompletionResult complete(CommandCompletionRequest request, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null || request.getArgumentIndex() != 0) {
                return CommandCompletionResult.empty();
            }
            String partial = request.getPartialToken().toLowerCase();
            List<CommandCompletion> out = new ArrayList<CommandCompletion>();
            for (com.aresstack.askai.research.state.ResearchCommandType type
                    : session.currentAllowedCommands()) {
                String kebab = type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                if (kebab.startsWith(partial)) {
                    out.add(new CommandCompletion(kebab, kebab,
                            "Allowed in the current state", CompletionKind.ARGUMENT_VALUE));
                }
            }
            return new CommandCompletionResult(out);
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String raw = invocation.getArgument(0).trim();
            if (raw.isEmpty()) {
                return CommandExecutionResult.rejected("Usage: /do <command>");
            }
            com.aresstack.askai.research.state.ResearchCommandType type;
            try {
                type = com.aresstack.askai.research.state.ResearchCommandType.valueOf(
                        raw.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException unknown) {
                return CommandExecutionResult.rejected("Unknown command: " + raw);
            }
            com.aresstack.askai.research.backend.ResearchCommandDispatchResult result =
                    session.dispatch(type, null);
            if (result.isAccepted()) {
                return CommandExecutionResult.handled("Dispatched " + raw + ".");
            }
            return CommandExecutionResult.rejected(result.getStatus() + ": " + result.getDetail());
        }
    }

    /**
     * {@code /search <query>} — run a USER web search over everything after the command. It is the typed twin of
     * a yellow scoping-suggestion click: the SAME phase-independent {@link ResearchAgentSession#requestManualWebSearch}
     * service, never a chat turn and never a state-machine command. Works in any phase.
     */
    private static final class SearchCommand extends Base {
        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of("search", "Run a web search for the given text", "/search <query>",
                    new CommandArgumentDescriptor("query", "What to search the web for", true));
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String query = String.join(" ", invocation.getArguments()).trim();
            if (query.isEmpty()) {
                return CommandExecutionResult.rejected("Usage: /search <query>");
            }
            session.requestManualWebSearch(query);
            // No command-result line: the visible "Websuche: <query>" breadcrumb is emitted (and persisted)
            // uniformly from the search's 'started' event, so the typed command and a suggestion click match.
            return CommandExecutionResult.handled("");
        }
    }

    /** {@code /open <artifact>} reveals an artifact tab; completes artifact ids. */
    private static final class OpenCommand extends Base {
        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of("open", "Open a research artifact", "/open <artifact>",
                    new CommandArgumentDescriptor("artifact", "The artifact id to open", true));
        }

        @Override
        public CommandCompletionResult complete(CommandCompletionRequest request, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null || request.getArgumentIndex() != 0) {
                return CommandCompletionResult.empty();
            }
            String partial = request.getPartialToken().toLowerCase();
            List<CommandCompletion> out = new ArrayList<CommandCompletion>();
            for (AgentArtifact artifact : session.getArtifacts()) {
                if (artifact.getId().toLowerCase().startsWith(partial)) {
                    out.add(new CommandCompletion(artifact.getId(), artifact.getId(),
                            artifact.getDisplayName(), CompletionKind.ARGUMENT_VALUE));
                }
            }
            return new CommandCompletionResult(out);
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String artifactId = invocation.getArgument(0).trim();
            if (artifactId.isEmpty()) {
                return CommandExecutionResult.rejected("Usage: /open <artifact>");
            }
            for (AgentArtifact artifact : session.getArtifacts()) {
                if (artifact.getId().equalsIgnoreCase(artifactId)) {
                    context.openArtifact(artifact.getId());
                    return CommandExecutionResult.handled("Opened " + artifact.getDisplayName() + ".");
                }
            }
            return CommandExecutionResult.rejected("Unknown artifact: " + artifactId);
        }
    }
}
