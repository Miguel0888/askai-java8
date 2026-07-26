package com.aresstack.askai.research.plugin;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.WorkspaceFactory;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceInstance;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.research.ui.ResearchWorkspaceInstance;

/** Stateless factory that creates a fresh, fully isolated research workspace per request. */
public final class ResearchWorkspaceFactory implements WorkspaceFactory {

    @Override
    public WorkspaceInstance createWorkspace(WorkspaceCreationRequest request, WorkspaceHostContext hostContext) {
        return new ResearchWorkspaceInstance(request, hostContext);
    }
}
