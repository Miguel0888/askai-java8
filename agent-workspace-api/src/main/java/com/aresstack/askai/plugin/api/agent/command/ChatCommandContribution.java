package com.aresstack.askai.plugin.api.agent.command;

import com.aresstack.askai.plugin.api.agent.AgentSessionContext;

/**
 * A slash command an agent contributes to the shared composer. Contributions are stateless and long-lived; the
 * active session is supplied per call through {@link AgentSessionContext}. Slash commands are user controls,
 * distinct from agent tools: they steer the session or the UI, they do not execute an agent capability.
 *
 * <p>The command name in {@link #getDescriptor()} must be unique within one agent's command set.</p>
 */
public interface ChatCommandContribution {

    ChatCommandDescriptor getDescriptor();

    /** Offer completions for the current fragment; return {@link CommandCompletionResult#empty()} if none. */
    CommandCompletionResult complete(CommandCompletionRequest request, AgentSessionContext context);

    /** Execute the (already parsed) invocation against the active session. */
    CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context);
}
