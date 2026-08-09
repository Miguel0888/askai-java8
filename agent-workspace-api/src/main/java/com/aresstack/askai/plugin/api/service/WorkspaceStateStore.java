package com.aresstack.askai.plugin.api.service;

/**
 * Small key/value store scoped to a plugin (and, where relevant, a workspace) for UI state such as divider
 * positions or the last selected tab. Values are strings; callers encode richer data themselves.
 */
public interface WorkspaceStateStore {

    /**
     * Keys starting with this prefix are APP-WIDE: a session-scoping store implementation must route
     * them straight to its shared/global backing (no per-session copy, no freeze-at-first-read).
     * For everything else the store may apply its own scoping semantics.
     */
    String GLOBAL_KEY_PREFIX = "global.";

    String get(String key, String defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    int getInt(String key, int defaultValue);

    void put(String key, String value);

    void putBoolean(String key, boolean value);

    void putInt(String key, int value);
}
