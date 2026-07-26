package com.aresstack.askai.plugin.api.lifecycle;

import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutContribution;

/**
 * A single opened workspace surface (stateful). All methods run on the EDT. {@code deactivate()} keeps the
 * state alive (the user switched to another host view); {@code dispose()} is idempotent and must stop every
 * timer, executor task and listener the workspace owns.
 */
public interface WorkspaceInstance {

    WorkspaceLayoutContribution getLayout();

    void activate();

    void deactivate();

    boolean isDirty();

    void requestClose(WorkspaceCloseCallback callback);

    void dispose();
}
