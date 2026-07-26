package com.aresstack.askai.plugin.api.lifecycle;

/**
 * Result channel for {@code WorkspaceInstance.requestClose(...)}: the workspace decides asynchronously (on
 * the EDT) whether it may close now, possibly after prompting the user about unsaved changes.
 */
public interface WorkspaceCloseCallback {

    /** The workspace may be deactivated and disposed. */
    void allowClose();

    /** The workspace should stay open (e.g. the user cancelled a discard-changes prompt). */
    void vetoClose(String reason);
}
