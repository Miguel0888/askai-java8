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
    private final String scopeId;
    private final String projectId;
    private final Map<String, String> startupParameters;

    public AgentSessionCreationRequest(String sessionId, String projectId,
                                       Map<String, String> startupParameters) {
        this(sessionId, "", projectId, startupParameters);
    }

    /**
     * @param sessionId the INTERNAL session key the host uses to keep sessions apart (today
     *                  {@code <agentId>#<scopeId>}); a plugin uses it for its own per-session storage.
     * @param scopeId   the host-side SCOPE this session belongs to — the chat session's stable id. Unlike
     *                  {@code sessionId} it is meaningful to the host, so it is the id a plugin publishes
     *                  when something outside AskAI has to address this session. Empty when unscoped.
     */
    public AgentSessionCreationRequest(String sessionId, String scopeId, String projectId,
                                       Map<String, String> startupParameters) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        this.sessionId = sessionId.trim();
        this.scopeId = scopeId == null ? "" : scopeId.trim();
        this.projectId = projectId == null ? "" : projectId;
        this.startupParameters = startupParameters == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(startupParameters));
    }

    public String getSessionId() {
        return sessionId;
    }

    /** The host scope (chat session id) this session belongs to, or "" when the host gave none. */
    public String getScopeId() {
        return scopeId;
    }

    public String getProjectId() {
        return projectId;
    }

    public Map<String, String> getStartupParameters() {
        return startupParameters;
    }
}
