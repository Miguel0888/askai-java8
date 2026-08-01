package com.aresstack.askai.plugin.api.agent;

import javax.swing.JComponent;

/**
 * A settings page an agent plugin contributes to the HOST's existing gear menu at the chat composer. The
 * host renders contributions only while this agent is the selected one of the CURRENT chat tab, and hands
 * in that tab's live {@link AgentSession} — the component reads and writes the SESSION's values (via the
 * session's own state store), so two tabs of the same agent never reconfigure each other.
 *
 * <p>The plugin decides WHICH settings it has, the session holds their VALUES, the host only decides WHERE
 * they are shown. This is deliberately not an artifact view: settings are not work products.</p>
 */
public interface AgentSettingsContribution {

    /** The category label in the settings dialog's navigation (e.g. "Research Agent"). */
    String getDisplayName();

    /**
     * Build the settings component for exactly this session, or {@code null} when this contribution does
     * not apply to it (wrong session type) — the host then omits the category entirely.
     */
    JComponent createSettingsComponent(AgentSession session);
}
