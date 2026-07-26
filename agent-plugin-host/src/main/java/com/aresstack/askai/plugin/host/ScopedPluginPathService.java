package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.PluginPathService;

import java.io.File;

/**
 * {@link PluginPathService} scoped to one plugin id under a base data directory. Directories are created on
 * demand; nothing outside {@code <dataDir>/plugin-data/<pluginId>} and {@code <dataDir>/workspaces/<pluginId>}
 * is exposed, so a plugin can never reach another plugin's data.
 */
public final class ScopedPluginPathService implements PluginPathService {

    private final File dataDirectory;
    private final String pluginId;

    public ScopedPluginPathService(File dataDirectory, String pluginId) {
        this.dataDirectory = dataDirectory;
        this.pluginId = safe(pluginId);
    }

    @Override
    public File getPluginDataDirectory() {
        return ensure(new File(new File(dataDirectory, "plugin-data"), pluginId));
    }

    @Override
    public File getWorkspaceDirectory(String workspaceInstanceId) {
        File base = new File(new File(dataDirectory, "workspaces"), pluginId);
        return ensure(new File(base, safe(workspaceInstanceId)));
    }

    private static File ensure(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Keep directory names to a safe subset so an id can never escape its scope. */
    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "_";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
