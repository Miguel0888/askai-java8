package com.aresstack.askai.java8.history;

import java.util.ArrayList;
import java.util.List;

/** One persisted chat message: its role, text, time, the model that produced it and any images. */
public final class ChatMessageRecord {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    /** A muted italic info/system breadcrumb (e.g. "Websuche: …") — persisted, but not a model turn. */
    public static final String ROLE_INFO = "info";

    private String role;
    private String text;
    private long createdAt;
    private String model;
    private List<AttachmentRecord> attachments = new ArrayList<AttachmentRecord>();

    /** Gson. */
    public ChatMessageRecord() {
    }

    public ChatMessageRecord(String role, String text, long createdAt, String model,
                             List<AttachmentRecord> attachments) {
        this.role = role;
        this.text = text;
        this.createdAt = createdAt;
        this.model = model;
        this.attachments = attachments != null ? attachments : new ArrayList<AttachmentRecord>();
    }

    public String getRole() { return role; }
    public String getText() { return text != null ? text : ""; }
    public long getCreatedAt() { return createdAt; }

    /** The model that produced an assistant message, or {@code null} for user messages. */
    public String getModel() { return model; }

    public List<AttachmentRecord> getAttachments() {
        return attachments != null ? attachments : new ArrayList<AttachmentRecord>();
    }

    public boolean isUser() { return ROLE_USER.equals(role); }
    public boolean isAssistant() { return ROLE_ASSISTANT.equals(role); }
    public boolean isInfo() { return ROLE_INFO.equals(role); }
}
