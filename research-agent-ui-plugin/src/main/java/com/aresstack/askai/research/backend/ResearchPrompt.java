package com.aresstack.askai.research.backend;

/** A user prompt submitted to a session, tied to the active section for context. */
public final class ResearchPrompt {

    private final String text;
    private final String activeSectionId;

    public ResearchPrompt(String text, String activeSectionId) {
        this.text = text == null ? "" : text;
        this.activeSectionId = activeSectionId == null ? "" : activeSectionId;
    }

    public String getText() {
        return text;
    }

    public String getActiveSectionId() {
        return activeSectionId;
    }
}
