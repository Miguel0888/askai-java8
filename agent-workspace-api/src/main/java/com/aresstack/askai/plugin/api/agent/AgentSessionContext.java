package com.aresstack.askai.plugin.api.agent;

import com.aresstack.askai.plugin.api.service.UiExecutor;

/**
 * Passed to a {@link com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution} when completing or
 * executing a slash command. It gives the (stateless) command access to the currently active session and a
 * host UI hook, so a command like {@code /approve} can steer the session and {@code /open outline} can reveal
 * an artifact — without the generic API knowing any agent-specific type. A plugin's own commands may downcast
 * {@link #getSession()} to their concrete session type (same plugin classloader).
 */
public interface AgentSessionContext {

    AgentSession getSession();

    /** Ask the host to reveal the artifact tab with this id in the shared artifact area. */
    void openArtifact(String artifactId);

    UiExecutor getUiExecutor();

    /**
     * Show content as a closable OVERLAY over the active chat's transcript (e.g. a generated
     * diagram). The host owns backdrop, plate and close control. Default: no-op, so headless or
     * test hosts need no overlay plumbing.
     */
    default void showTranscriptOverlay(javax.swing.JComponent content, String title) {
    }

    /**
     * Show a MERMAID diagram as a transcript overlay. Takes the SOURCE, not a component: the host
     * owns the full viewer (render pipeline, zoom/pan, high-res re-render, copy/save), so plugins
     * never re-implement diagram display. Default: no-op.
     */
    default void showDiagramOverlay(String mermaidSource, String title) {
    }
}
