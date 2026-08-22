package com.aresstack.askai.plugin.api.service;

/**
 * READ-ONLY host port over the chat sessions (the tabs the user sees). A plugin uses it to learn which chat
 * is currently selected and to put a display title on a session id it already owns — it can neither open,
 * switch nor close chats through this port.
 * <p>
 * Implementations must be callable from ANY thread (an MCP worker never touches the UI toolkit): the active
 * session id is published by the UI and read without blocking.
 */
public interface ChatSessionCatalog {

    /** The chat session id (UUID) currently selected in the UI, or an empty string when none is. */
    String getActiveSessionId();

    /** Metadata for this chat id, or {@code null} when the host does not know it. */
    ChatSessionMetadata getSession(String sessionId);
}
