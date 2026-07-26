package com.aresstack.askai.plugin.api;

/**
 * Immutable, framework-free description of a workspace plugin. Carries no Swing component and no PF4J or
 * classloader type — only the metadata the host needs to list, order and validate a plugin.
 */
public final class WorkspacePluginDescriptor {

    private final String id;
    private final String displayName;
    private final String description;
    private final String version;
    private final int pluginApiVersion;
    private final String provider;
    private final String iconKey;
    private final int displayOrder;

    private WorkspacePluginDescriptor(Builder builder) {
        this.id = require(builder.id, "id");
        this.displayName = require(builder.displayName, "displayName");
        this.description = builder.description == null ? "" : builder.description;
        this.version = require(builder.version, "version");
        this.pluginApiVersion = builder.pluginApiVersion;
        this.provider = builder.provider == null ? "" : builder.provider;
        this.iconKey = builder.iconKey == null ? "" : builder.iconKey;
        this.displayOrder = builder.displayOrder;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public int getPluginApiVersion() {
        return pluginApiVersion;
    }

    public String getProvider() {
        return provider;
    }

    public String getIconKey() {
        return iconKey;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }

    /** Fluent builder; keeps the descriptor immutable while allowing optional fields. */
    public static final class Builder {
        private String id;
        private String displayName;
        private String description;
        private String version;
        private int pluginApiVersion = 1;
        private String provider;
        private String iconKey;
        private int displayOrder;

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder version(String value) {
            this.version = value;
            return this;
        }

        public Builder pluginApiVersion(int value) {
            this.pluginApiVersion = value;
            return this;
        }

        public Builder provider(String value) {
            this.provider = value;
            return this;
        }

        public Builder iconKey(String value) {
            this.iconKey = value;
            return this;
        }

        public Builder displayOrder(int value) {
            this.displayOrder = value;
            return this;
        }

        public WorkspacePluginDescriptor build() {
            return new WorkspacePluginDescriptor(this);
        }
    }
}
