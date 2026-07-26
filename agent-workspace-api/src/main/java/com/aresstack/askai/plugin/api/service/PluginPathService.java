package com.aresstack.askai.plugin.api.service;

import java.io.File;

/**
 * Supplies the controlled, plugin-scoped data directories so a plugin never assumes its own paths. The host
 * creates the directories on demand; plugins must not scan outside them.
 */
public interface PluginPathService {

    /** Per-plugin data directory: {@code <data>/plugin-data/<plugin-id>}. */
    File getPluginDataDirectory();

    /** Per-workspace directory: {@code <data>/workspaces/<plugin-id>/<workspace-id>}. */
    File getWorkspaceDirectory(String workspaceInstanceId);
}
