package com.aresstack.askai.plugin.api.service;

/**
 * Small key/value store scoped to a plugin (and, where relevant, a workspace) for UI state such as divider
 * positions or the last selected tab. Values are strings; callers encode richer data themselves.
 */
public interface WorkspaceStateStore {

    String get(String key, String defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    int getInt(String key, int defaultValue);

    void put(String key, String value);

    void putBoolean(String key, boolean value);

    void putInt(String key, int value);
}
