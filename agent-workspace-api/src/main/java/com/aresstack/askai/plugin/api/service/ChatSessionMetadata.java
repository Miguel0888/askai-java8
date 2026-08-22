package com.aresstack.askai.plugin.api.service;

/**
 * Read-only metadata of ONE host chat session (a chat tab), identified by its stable UUID. The title is
 * display-only and explicitly NOT an identity: two chats may carry the same title.
 */
public final class ChatSessionMetadata {

    private final String sessionId;
    private final String title;
    private final long modifiedAt;

    public ChatSessionMetadata(String sessionId, String title, long modifiedAt) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.title = title == null ? "" : title;
        this.modifiedAt = modifiedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** The chat's display title, or an empty string for a chat that has none yet. */
    public String getTitle() {
        return title;
    }

    /** Last modification timestamp (epoch millis), or 0 for a chat that was never persisted. */
    public long getModifiedAt() {
        return modifiedAt;
    }
}
