package com.aresstack.askai.plugin.host;

import java.util.List;

/**
 * The single source of truth for the chat interaction mode (Yapping/Questing) and the selected Questing
 * agent. Multiple bound views (one per workspace composer) read and drive this same controller; a change
 * from any view updates the shared state and notifies all views.
 */
public interface WorkspaceModeController {

    /** @return {@link WorkspaceModeEntry#YAPPING_ID} or {@link WorkspaceModeEntry#QUESTING_ID}. */
    String getInteractionMode();

    /** @return the selected agent id, or {@code null} if none. */
    String getActiveAgentId();

    /**
     * The active agent's display name, resolvable even before the async plugin catalog has loaded (from a
     * persisted label), so a restart renders the exact agent instead of a generic "Questing" flicker.
     * Returns null when there is no active agent.
     */
    String getActiveAgentLabel();

    /** @return a snapshot of the currently available agents. */
    List<WorkspaceModeEntry> getAvailableAgents();

    boolean hasAgents();

    void setInteractionMode(String modeId);

    void selectAgent(String agentId);

    /** Registers a listener notified on the EDT whenever mode, agent or the agent list changes. */
    void addChangeListener(Runnable listener);

    void removeChangeListener(Runnable listener);
}
