package com.aresstack.askai.plugin.api.service;

import java.util.List;

/**
 * READ-ONLY host port over the PERSISTED messages of a chat — deliberately separate from
 * {@link ChatSessionCatalog} (which answers "which session, which title, which one is selected"), so a
 * consumer that only needs session metadata does not depend on message access.
 * <p>
 * The host's chat store is the ONE truth for message text. A plugin that adds its own view on the
 * conversation (e.g. research phases) keys that metadata by {@link ChatMessageSnapshot#getMessageId()}
 * instead of keeping a second copy of the text, which would inevitably drift.
 * <p>
 * Implementations must be callable from ANY thread and must reflect messages persisted so far, including
 * those of the currently running conversation.
 */
public interface ChatSessionHistoryReader {

    /**
     * Every persisted message of this chat in conversation order; an empty list for an unknown chat or one
     * that has no messages yet.
     */
    List<ChatMessageSnapshot> readMessages(String sessionId);
}
