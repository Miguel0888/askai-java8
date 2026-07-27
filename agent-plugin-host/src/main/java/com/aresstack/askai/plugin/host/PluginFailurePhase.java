package com.aresstack.askai.plugin.host;

/**
 * Classifies where a plugin problem occurred, so the plugin-management UI can explain it instead of showing
 * a raw stack trace. Kept coarse and stable.
 */
public enum PluginFailurePhase {
    EXTENSION_DISCOVERY,
    DESCRIPTOR_VALIDATION,
    WORKSPACE_CREATION,
    WORKSPACE_ACTIVATION,
    WORKSPACE_DEACTIVATION,
    WORKSPACE_DISPOSAL,
    /** A plugin start threw during a candidate build. */
    PLUGIN_START,
    /** Closing the outgoing generation's sessions failed, so the swap was aborted. */
    SESSION_CLOSE,
    /** Stopping/unloading a retiring generation failed; it is kept for a later retry. */
    GENERATION_RETIREMENT
}
