package com.aresstack.askai.plugin.host;

/**
 * A captured, isolated plugin failure. Holds a user-facing message plus the technical cause for logs and a
 * collapsible details view, so one broken plugin never stops AskAI or the normal chat from starting.
 */
public final class PluginLoadFailure {

    private final String pluginPath;
    private final String pluginId;
    private final PluginFailurePhase phase;
    private final String publicMessage;
    private final Throwable technicalCause;

    public PluginLoadFailure(String pluginPath, String pluginId, PluginFailurePhase phase,
                             String publicMessage, Throwable technicalCause) {
        this.pluginPath = pluginPath == null ? "" : pluginPath;
        this.pluginId = pluginId == null ? "" : pluginId;
        this.phase = phase;
        this.publicMessage = publicMessage == null ? "" : publicMessage;
        this.technicalCause = technicalCause;
    }

    public String getPluginPath() {
        return pluginPath;
    }

    /** @return the plugin id if it could be read, otherwise an empty string. */
    public String getPluginId() {
        return pluginId;
    }

    public PluginFailurePhase getPhase() {
        return phase;
    }

    public String getPublicMessage() {
        return publicMessage;
    }

    public Throwable getTechnicalCause() {
        return technicalCause;
    }
}
