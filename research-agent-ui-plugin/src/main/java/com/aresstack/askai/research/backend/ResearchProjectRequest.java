package com.aresstack.askai.research.backend;

/** Request to open a research session; carries stable ids from the start. */
public final class ResearchProjectRequest {

    private final String sessionId;
    private final String projectId;
    private final String title;

    public ResearchProjectRequest(String sessionId, String projectId, String title) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        this.sessionId = sessionId;
        this.projectId = projectId == null ? "" : projectId;
        this.title = title == null ? "" : title;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }
}
