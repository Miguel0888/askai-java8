package com.aresstack.askai.plugin.api.service;

/**
 * The one WRITING chat port: create a new chat and put an agent in charge of it. Deliberately separate from
 * the read-only {@link ChatSessionCatalog} and {@link ChatSessionHistoryReader} — a consumer that only reads
 * must not depend on a port that changes what the user sees.
 * <p>
 * Creating a chat DOES bring it to the foreground: a new chat that stayed invisible would be a surprise, and
 * unlike addressing an existing session (which deliberately never switches the visible chat) creating one is
 * an explicit, user-visible act. Callers must treat it as such and never do it implicitly.
 * <p>
 * Implementations may be called from ANY thread and marshal onto the UI thread themselves; the call returns
 * only once the chat exists and its agent has been activated.
 */
public interface ChatSessionLauncher {

    /**
     * @param agentId the agent to activate in the new chat (the workspace switches to agent mode for it)
     * @param title   an optional display title; empty for none. The title becomes visible once the chat has
     *                content — an empty chat is not persisted.
     * @return the new chat session id, or "" when the host could not create one
     */
    String createChatSession(String agentId, String title);
}
