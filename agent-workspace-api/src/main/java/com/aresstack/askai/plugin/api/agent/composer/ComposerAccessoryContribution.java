package com.aresstack.askai.plugin.api.agent.composer;

import com.aresstack.askai.plugin.api.agent.AgentSession;

/**
 * Contributes a persistent {@link ComposerAccessory} shown above the chat composer while this agent's session
 * is active. Generic host SPI (not tied to any one agent): the host asks the active agent extension for its
 * accessories, keeps only those whose {@link #supports(AgentSession)} accepts the live session, builds them
 * with a {@link ComposerAccessoryContext}, and disposes them on session/agent/tab change.
 */
public interface ComposerAccessoryContribution {

    /** A stable id for this accessory (diagnostics / de-duplication). */
    String getId();

    /** Whether this accessory applies to the given live session (e.g. the right agent type). */
    boolean supports(AgentSession session);

    /** Build the accessory for the active session; called on the EDT. */
    ComposerAccessory create(ComposerAccessoryContext context);
}
