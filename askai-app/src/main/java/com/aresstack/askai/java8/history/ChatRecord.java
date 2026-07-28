package com.aresstack.askai.java8.history;

import java.util.ArrayList;
import java.util.List;

/**
 * One persisted chat conversation, keyed by its stable UUID (the chat-tab identity).  Holds the
 * ordered messages plus the metadata needed to restore the chat's context: title, timestamps and
 * the model/mode/agent/system-prompt selection.
 */
public final class ChatRecord {

    private String id;
    private String title;
    private long createdAt;
    private long modifiedAt;
    private String model;
    private String mode;
    private String agent;
    private String systemPrompt;
    private List<ChatMessageRecord> messages = new ArrayList<ChatMessageRecord>();

    /** Gson. */
    public ChatRecord() {
    }

    public ChatRecord(String id, long createdAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.modifiedAt = createdAt;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public long getCreatedAt() { return createdAt; }
    public long getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(long modifiedAt) { this.modifiedAt = modifiedAt; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public List<ChatMessageRecord> getMessages() {
        if (messages == null) {
            messages = new ArrayList<ChatMessageRecord>();
        }
        return messages;
    }

    public boolean isEmpty() {
        return getMessages().isEmpty();
    }
}
