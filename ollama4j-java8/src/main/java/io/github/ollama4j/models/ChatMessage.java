package io.github.ollama4j.models;

public final class ChatMessage {

    private final String role;
    private final String content;

    public ChatMessage(String role, String content) {
        this.role = role == null ? "user" : role;
        this.content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
