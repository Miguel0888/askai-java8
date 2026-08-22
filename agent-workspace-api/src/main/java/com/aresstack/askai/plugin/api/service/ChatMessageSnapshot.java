package com.aresstack.askai.plugin.api.service;

/**
 * One persisted conversation message of a chat, as the host stores it. This is a READ projection: the host's
 * chat record stays the single source of truth for the message TEXT — a consumer that needs more (e.g. a
 * research phase) keeps its own metadata keyed by {@link #getMessageId()} instead of copying the text.
 */
public final class ChatMessageSnapshot {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    /** A muted italic info/system breadcrumb (e.g. "Websuche: …") — persisted, but not a model turn. */
    public static final String ROLE_INFO = "info";

    private final String messageId;
    private final String role;
    private final String text;
    private final long createdAt;
    private final String model;

    public ChatMessageSnapshot(String messageId, String role, String text, long createdAt, String model) {
        this.messageId = messageId == null ? "" : messageId;
        this.role = role == null ? "" : role;
        this.text = text == null ? "" : text;
        this.createdAt = createdAt;
        this.model = model == null ? "" : model;
    }

    /**
     * The stable id of this message, or "" for a message persisted before ids existed (or one the host
     * created without one). Consumers must treat an absent id as "no metadata known" — never guess by text
     * or timestamp.
     */
    public String getMessageId() {
        return messageId;
    }

    /** user / assistant / info. */
    public String getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** The model that produced an assistant message, or "". */
    public String getModel() {
        return model;
    }
}
