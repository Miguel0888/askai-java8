package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/**
 * SESSION-scoped settings with the agent-global values as read-only TEMPLATE: reads answer from the
 * session scope when a value was ever written there, else from the agent scope (so a fresh session starts
 * from the familiar values); writes go EXCLUSIVELY to the session scope. Two chat tabs of the same agent
 * therefore never reconfigure each other, and a restored session (same stable scope id) finds exactly its
 * own values again.
 */
public final class SessionScopedWorkspaceStateStore implements WorkspaceStateStore {

    private final WorkspaceStateStore agentTemplate;
    private final WorkspaceStateStore session;

    public SessionScopedWorkspaceStateStore(WorkspaceStateStore agentTemplate,
                                            WorkspaceStateStore session) {
        this.agentTemplate = agentTemplate;
        this.session = session;
    }

    @Override
    public String get(String key, String defaultValue) {
        String own = session.get(key, null);
        return own != null ? own : agentTemplate.get(key, defaultValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String own = session.get(key, null);
        return own != null ? Boolean.parseBoolean(own) : agentTemplate.getBoolean(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String own = session.get(key, null);
        if (own != null) {
            try {
                return Integer.parseInt(own);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }
        return agentTemplate.getInt(key, defaultValue);
    }

    @Override
    public void put(String key, String value) {
        session.put(key, value);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        session.putBoolean(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        session.putInt(key, value);
    }
}
