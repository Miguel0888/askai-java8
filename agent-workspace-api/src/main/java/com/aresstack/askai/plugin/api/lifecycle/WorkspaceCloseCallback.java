package com.aresstack.askai.plugin.api.lifecycle;

/**
 * Result channel for {@code WorkspaceInstance.requestClose(...)}: the workspace decides asynchronously
 * whether it may close now, possibly after prompting the user about unsaved changes.
 *
 * <p>Contract: exactly one of {@link #allowClose()} / {@link #vetoClose(String)} takes effect, and only the
 * first invocation counts — the host guards this (e.g. with an {@code AtomicBoolean}) and ignores later or
 * duplicate calls. The workspace may answer later, off the initial call stack; the host may show a
 * transient "Closing…" state meanwhile. On application shutdown the host must not block indefinitely on an
 * unanswered callback.</p>
 */
public interface WorkspaceCloseCallback {

    /** The workspace may be deactivated and disposed. */
    void allowClose();

    /** The workspace should stay open (e.g. the user cancelled a discard-changes prompt). */
    void vetoClose(String reason);
}
