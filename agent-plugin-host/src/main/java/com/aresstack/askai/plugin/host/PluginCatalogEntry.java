package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

/**
 * One row of the plugin catalog as shown in plugin management: the descriptor (may be {@code null} if the
 * plugin failed before a descriptor could be read), its compatibility verdict, PF4J plugin state, on-disk
 * location, a content hash, whether a restart is required, and the last failure (if any).
 */
public final class PluginCatalogEntry {

    private final String pluginId;
    private final WorkspacePluginDescriptor descriptor;
    private final PluginCompatibility compatibility;
    private final String pluginState;
    private final String location;
    private final String sha256;
    private final boolean enabled;
    private final boolean restartRequired;
    private final PluginLoadFailure lastError;

    private PluginCatalogEntry(Builder b) {
        this.pluginId = b.pluginId == null ? "" : b.pluginId;
        this.descriptor = b.descriptor;
        this.compatibility = b.compatibility;
        this.pluginState = b.pluginState == null ? "" : b.pluginState;
        this.location = b.location == null ? "" : b.location;
        this.sha256 = b.sha256 == null ? "" : b.sha256;
        this.enabled = b.enabled;
        this.restartRequired = b.restartRequired;
        this.lastError = b.lastError;
    }

    public String getPluginId() {
        return pluginId;
    }

    public WorkspacePluginDescriptor getDescriptor() {
        return descriptor;
    }

    public PluginCompatibility getCompatibility() {
        return compatibility;
    }

    public String getPluginState() {
        return pluginState;
    }

    public String getLocation() {
        return location;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }

    public PluginLoadFailure getLastError() {
        return lastError;
    }

    /** @return true when this plugin may be offered in the normal mode selector. */
    public boolean isSelectable() {
        return enabled && compatibility == PluginCompatibility.COMPATIBLE && lastError == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String pluginId;
        private WorkspacePluginDescriptor descriptor;
        private PluginCompatibility compatibility = PluginCompatibility.COMPATIBLE;
        private String pluginState;
        private String location;
        private String sha256;
        private boolean enabled = true;
        private boolean restartRequired;
        private PluginLoadFailure lastError;

        public Builder pluginId(String v) {
            this.pluginId = v;
            return this;
        }

        public Builder descriptor(WorkspacePluginDescriptor v) {
            this.descriptor = v;
            return this;
        }

        public Builder compatibility(PluginCompatibility v) {
            this.compatibility = v;
            return this;
        }

        public Builder pluginState(String v) {
            this.pluginState = v;
            return this;
        }

        public Builder location(String v) {
            this.location = v;
            return this;
        }

        public Builder sha256(String v) {
            this.sha256 = v;
            return this;
        }

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder restartRequired(boolean v) {
            this.restartRequired = v;
            return this;
        }

        public Builder lastError(PluginLoadFailure v) {
            this.lastError = v;
            return this;
        }

        public PluginCatalogEntry build() {
            return new PluginCatalogEntry(this);
        }
    }
}
