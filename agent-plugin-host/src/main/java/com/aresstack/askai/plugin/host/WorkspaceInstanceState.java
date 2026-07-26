package com.aresstack.askai.plugin.host;

/**
 * Host-side lifecycle state of a single workspace instance. Distinct from PF4J's plugin state (CREATED,
 * DISABLED, RESOLVED, STARTED, STOPPED): this tracks the opened UI surface, not the plugin bundle.
 */
public enum WorkspaceInstanceState {
    CREATED,
    ACTIVE,
    INACTIVE,
    CLOSING,
    DISPOSED,
    FAILED
}
