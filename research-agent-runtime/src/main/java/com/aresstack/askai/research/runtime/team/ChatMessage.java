package com.aresstack.askai.research.runtime.team;

/**
 * One message in a main-model chat exchange (Ollama {@code /api/chat} {@code messages[]} shape): a role and
 * its text content. Immutable; the TeamAgent keeps a per-session list of these as its conversation history.
 */
public final class ChatMessage {

    /** The Ollama chat roles the TeamAgent uses. */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final Role role;
    private final String content;

    public ChatMessage(Role role, String content) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        this.role = role;
        this.content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
