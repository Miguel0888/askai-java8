package com.aresstack.askai.plugin.api.agent;

/**
 * Creates stateful {@link AgentSession}s. The factory itself is expected to be stateless and long-lived; host
 * services are handed in per creation via {@link AgentHostContext}, never stored globally. Called on the UI
 * thread.
 *
 * <p>Two-argument shape (request + host context) mirrors the existing {@code WorkspaceFactory} for
 * consistency, rather than folding the host ports into the request.</p>
 */
public interface AgentSessionFactory {

    AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext);
}
