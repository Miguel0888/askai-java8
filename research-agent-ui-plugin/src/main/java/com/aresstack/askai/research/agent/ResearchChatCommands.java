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
        // ONE synchronized command surface: the TEXT adapters — /search (user web search) and /open
        // (artifact navigation) — plus one slash command per SEMANTIC state command. The semantic
        // names (submit-scope, approve, …) ARE user API (they are the red tags' ids and the MCP
        // vocabulary); internal ResearchCommandType enum names still never are. Every semantic slash
        // command executes through the session's ONE structured command processor — exactly what a
        // red-tag click runs, so /submit-scope and "Fragestellung freigeben & weiter" are twins.
        List<ChatCommandContribution> commands = new ArrayList<ChatCommandContribution>();
        commands.add(new OpenCommand());
        commands.add(new SearchCommand());
        commands.add(new SemanticStateCommand("submit-scope",
                "Approve the research brief and continue into the research phase"));
        commands.add(new SemanticStateCommand("approve",
                "Approve the pending review gate (outline/evidence/draft/final)"));
        commands.add(new SemanticStateCommand("request-changes",
                "Request changes at the pending review gate"));
        commands.add(new SemanticStateCommand("continue",
                "Continue with the next step (start research/drafting)"));
        commands.add(new SemanticStateCommand("pause", "Pause the running research"));
        commands.add(new SemanticStateCommand("resume", "Continue where the research paused"));
        commands.add(new SemanticStateCommand("retry", "Retry the step that failed"));
        commands.add(new SemanticStateCommand("cancel", "Cancel this research session"));
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

    /**
     * A slash twin of a red action tag: {@code /<semantic-name>} runs the SAME semantic command
     * through {@link ResearchAgentSession#executeCommand} that a tag click runs — one processor,
     * no side paths. The processor's honest "handled:/rejected:" answer becomes the chat feedback,
     * so a command that is not allowed in the current phase says so instead of doing nothing.
     */
    private static final class SemanticStateCommand extends Base {
        private final String name;
        private final String description;

        SemanticStateCommand(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of(name, description);
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String arguments = String.join(" ", invocation.getArguments()).trim();
            String outcome = session.executeCommand(name, arguments);
            if (outcome == null) {
                return CommandExecutionResult.rejected("The command produced no result.");
            }
            if (outcome.startsWith("handled")) {
                return CommandExecutionResult.handled(stripPrefix(outcome, "handled:"));
            }
            return CommandExecutionResult.rejected(stripPrefix(outcome, "rejected:"));
        }

        private static String stripPrefix(String outcome, String prefix) {
            return outcome.startsWith(prefix) ? outcome.substring(prefix.length()).trim() : outcome;
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
