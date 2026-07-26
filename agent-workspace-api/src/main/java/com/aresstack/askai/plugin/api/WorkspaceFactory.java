package com.aresstack.askai.plugin.api;

import com.aresstack.askai.plugin.api.lifecycle.WorkspaceInstance;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;

/**
 * Creates stateful {@link WorkspaceInstance}s. The factory itself is expected to be stateless and long-lived;
 * host services are handed in per creation, never stored globally. Called on the EDT.
 */
public interface WorkspaceFactory {

    WorkspaceInstance createWorkspace(WorkspaceCreationRequest request, WorkspaceHostContext hostContext);
}
