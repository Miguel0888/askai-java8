package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult;

import java.util.List;

/**
 * The slash commands available for the currently active agent. The composer consults this to decide whether a
 * {@code /}-line is a command, to offer completions, and to execute it. Backed by the active agent's
 * {@link com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension} contributions; empty when no agent is
 * active (Yapping / no agent), so the composer treats {@code /} as ordinary text there. No domain type leaks.
 */
public interface ActiveAgentCommandRegistry {

    /** @return the descriptors of the active agent's commands (empty when no agent is active). */
    List<ChatCommandDescriptor> getCommands();

    /**
     * Compute completions for the given editor input and caret position. Each returned completion's
     * {@code insertionText} is the FULL replacement line for the editor (the registry reconstructs the line),
     * so the composer just replaces its text. Returns {@link CommandCompletionResult#empty()} when the input
     * is not a command line or no agent is active.
     */
    CommandCompletionResult complete(String input, int caretPosition);

    /**
     * Execute the command in {@code input} (a whole {@code /...} line) against the active agent. Returns an
     * {@code UNKNOWN} result when the line is not a command / no agent is active / the command is unknown.
     */
    CommandExecutionResult execute(String input);

    /** @return whether {@code input} is a slash-command line AND an agent with commands is active. */
    boolean isCommandLine(String input);
}
