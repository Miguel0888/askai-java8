package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/**
 * Session-scoped settings with LAST-SETTING-WINS defaults for new sessions:
 *
 * <ul>
 *   <li><b>Reads</b> answer from the session scope; a value the session never saw is resolved from the
 *       agent template (or the caller default) and immediately MATERIALIZED into the session scope — the
 *       session is frozen at first use, so later template changes never reconfigure an existing chat.</li>
 *   <li><b>Writes</b> go to the session scope AND to the agent template: the user's latest choice is what
 *       every NEW chat starts with, while already-frozen sessions keep their own values.</li>
 * </ul>
 *
 * No key enumeration is required: freezing happens lazily per key on first read, which covers exactly the
 * keys a session actually uses.
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
        if (own != null) {
            return own;
        }
        String inherited = agentTemplate.get(key, null);
        String resolved = inherited != null ? inherited : defaultValue;
        if (resolved != null) {
            session.put(key, resolved); // freeze at first read: this chat keeps ITS settings
        }
        return resolved;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    @Override
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException invalid) {
            return defaultValue;
        }
    }

    @Override
    public void put(String key, String value) {
        session.put(key, value);
        agentTemplate.put(key, value); // the user's LAST setting is the default for NEW chats
    }

    @Override
    public void putBoolean(String key, boolean value) {
        put(key, String.valueOf(value));
    }

    @Override
    public void putInt(String key, int value) {
        put(key, String.valueOf(value));
    }
}
