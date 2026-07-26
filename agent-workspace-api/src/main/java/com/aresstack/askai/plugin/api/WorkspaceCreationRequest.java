package com.aresstack.askai.plugin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable request the host passes when asking a plugin to open a workspace. IDs are carried from the
 * start (even if a fresh clickdummy leaves {@code projectId} empty) so multiple projects/sessions can be
 * opened later without shared global plugin state.
 */
public final class WorkspaceCreationRequest {

    private final String workspaceInstanceId;
    private final String projectId;
    private final Map<String, String> startupParameters;

    public WorkspaceCreationRequest(String workspaceInstanceId, String projectId,
                                    Map<String, String> startupParameters) {
        if (workspaceInstanceId == null || workspaceInstanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceInstanceId must not be empty");
        }
        this.workspaceInstanceId = workspaceInstanceId;
        this.projectId = projectId == null ? "" : projectId;
        this.startupParameters = startupParameters == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(startupParameters));
    }

    public String getWorkspaceInstanceId() {
        return workspaceInstanceId;
    }

    public String getProjectId() {
        return projectId;
    }

    public Map<String, String> getStartupParameters() {
        return startupParameters;
    }
}
