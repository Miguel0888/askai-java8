package com.aresstack.askai.plugin.api.agent.toolbar;

import com.aresstack.askai.plugin.api.agent.AgentSession;

import javax.swing.JComponent;

/**
 * Contributes a small session-scoped control to the workspace TOP BAR (left of the gear) while this agent's
 * session is active — e.g. a research language switch, a coding agent's branch selector. Generic host SPI
 * like {@link com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution}: the host asks the
 * active agent extension, keeps only contributions whose {@link #supports(AgentSession)} accepts the live
 * session, builds them on the EDT and discards them on session/agent/tab change. Session-based by contract:
 * the component works on the session's values, never on agent-global state.
 */
public interface AgentToolbarContribution {

    /** A stable id for this control (diagnostics / de-duplication). */
    String getId();

    /** Whether this control applies to the given live session (e.g. the right agent type). */
    boolean supports(AgentSession session);

    /** Build the control for the active session; called on the EDT. */
    JComponent createComponent(AgentToolbarContext context);
}
