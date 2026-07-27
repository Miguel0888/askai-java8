package com.aresstack.askai.plugin.api.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable request the host passes when opening an agent session. IDs are carried from the start so multiple
 * sessions/projects can coexist without shared global plugin state.
 */
public final class AgentSessionCreationRequest {

    private final String sessionId;
    private final String projectId;
    private final Map<String, String> startupParameters;

    public AgentSessionCreationRequest(String sessionId, String projectId,
                                       Map<String, String> startupParameters) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        this.sessionId = sessionId.trim();
        this.projectId = projectId == null ? "" : projectId;
        this.startupParameters = startupParameters == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(startupParameters));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProjectId() {
        return projectId;
    }

    public Map<String, String> getStartupParameters() {
        return startupParameters;
    }
}
