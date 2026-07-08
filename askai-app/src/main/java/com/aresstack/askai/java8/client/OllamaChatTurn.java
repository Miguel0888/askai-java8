package com.aresstack.askai.java8.client;

/**
 * One message in an Ollama chat conversation, in AskAI domain terms.
 *
 * <p>Roles are the Ollama wire roles ({@code system}, {@code user}, {@code assistant}).
 * The adapter maps these to ollama4j {@code OllamaChatMessageRole} values.</p>
 */
public final class OllamaChatTurn {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private final String role;
    private final String content;

    public OllamaChatTurn(String role, String content) {
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
    }

    public static OllamaChatTurn system(String content) {
        return new OllamaChatTurn(ROLE_SYSTEM, content);
    }

    public static OllamaChatTurn user(String content) {
        return new OllamaChatTurn(ROLE_USER, content);
    }

    public static OllamaChatTurn assistant(String content) {
        return new OllamaChatTurn(ROLE_ASSISTANT, content);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public boolean isUser() {
        return ROLE_USER.equalsIgnoreCase(role);
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equalsIgnoreCase(role);
    }

    public boolean isSystem() {
        return ROLE_SYSTEM.equalsIgnoreCase(role);
    }
}
