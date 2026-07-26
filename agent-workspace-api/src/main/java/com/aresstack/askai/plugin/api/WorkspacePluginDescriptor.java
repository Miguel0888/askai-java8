package com.aresstack.askai.plugin.api;

import java.util.regex.Pattern;

/**
 * Immutable, framework-free description of a workspace plugin. Carries no Swing component and no PF4J or
 * classloader type — only the metadata the host needs to list, order and validate a plugin.
 *
 * <p>All string fields are trimmed. {@code id} must be a stable, whitespace-free, reverse-domain-style id
 * and {@code pluginApiVersion} must be &ge; 1. {@code displayOrder} may be negative (to sort a plugin to the
 * front).</p>
 */
public final class WorkspacePluginDescriptor {

    /** Alphanumeric segments joined by {@code . _ -}, at least two segments (e.g. com.aresstack.askai.research). */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9]+(?:[._-][a-zA-Z0-9]+)+$");

    private final String id;
    private final String displayName;
    private final String description;
    private final String version;
    private final int pluginApiVersion;
    private final String provider;
    private final String iconKey;
    private final int displayOrder;

    private WorkspacePluginDescriptor(Builder builder) {
        this.id = requireId(builder.id);
        this.displayName = require(builder.displayName, "displayName");
        this.description = trimOrEmpty(builder.description);
        this.version = require(builder.version, "version");
        if (builder.pluginApiVersion < 1) {
            throw new IllegalArgumentException("pluginApiVersion must be >= 1, was " + builder.pluginApiVersion);
        }
        this.pluginApiVersion = builder.pluginApiVersion;
        this.provider = trimOrEmpty(builder.provider);
        this.iconKey = trimOrEmpty(builder.iconKey);
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
        return value.trim();
    }

    private static String requireId(String value) {
        String id = require(value, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be a stable reverse-domain-style identifier "
                    + "(alphanumeric segments joined by . _ -), was: " + id);
        }
        return id;
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
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
