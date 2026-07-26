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
    WORKSPACE_DISPOSAL
}
