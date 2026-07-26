package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists which plugins the user disabled, keyed by stable plugin id (never a display name or index). A
 * disabled plugin is dropped from the selectable catalog (and thus from the Questing agent list); the
 * default is enabled. Backed by a host-level {@link WorkspaceStateStore} so it survives restarts.
 */
public final class PluginEnablementService {

    private static final String KEY = "plugins.disabledIds";
    private static final String SEPARATOR = "\n";

    private final WorkspaceStateStore store;

    public PluginEnablementService(WorkspaceStateStore store) {
        this.store = store;
    }

    public boolean isEnabled(String pluginId) {
        return pluginId != null && !disabledIds().contains(pluginId);
    }

    public void setEnabled(String pluginId, boolean enabled) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            return;
        }
        Set<String> disabled = disabledIds();
        if (enabled) {
            disabled.remove(pluginId);
        } else {
            disabled.add(pluginId);
        }
        store.put(KEY, join(disabled));
    }

    public Set<String> disabledIds() {
        Set<String> ids = new LinkedHashSet<String>();
        String raw = store == null ? "" : store.get(KEY, "");
        if (raw != null && raw.length() > 0) {
            for (String id : raw.split(SEPARATOR)) {
                if (id.trim().length() > 0) {
                    ids.add(id.trim());
                }
            }
        }
        return ids;
    }

    private static String join(Set<String> ids) {
        StringBuilder builder = new StringBuilder();
        for (String id : ids) {
            if (builder.length() > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(id);
        }
        return builder.toString();
    }
}
