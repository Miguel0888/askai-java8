package com.aresstack.askai.research.mcp;

import com.aresstack.askai.plugin.api.service.ChatSessionCatalog;
import com.aresstack.askai.plugin.api.service.ChatSessionMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE directory of all currently LIVE research sessions — the thing the public connector talks to instead of
 * holding one "current" gateway. A session registers itself when it starts and unregisters when it closes,
 * INDEPENDENTLY of whether the public connector is switched on: the session lifecycle and the listener
 * lifecycle are separate concerns, so turning the connector on later still sees everything that is running.
 * <p>
 * It contains exactly the sessions that can still be driven — never stored-but-closed chats, never a session
 * that is being torn down. The public key is the host's chat UUID (see {@link ChatSessionCatalog}), never the
 * chat title: two chats may carry the identical title, so a title can never be an identity.
 * <p>
 * A plugin-classloader singleton, like the connector runtime it serves; {@link #clear()} belongs to the
 * plugin's own stop().
 */
public final class ResearchBotSessionDirectory {

    private static final ResearchBotSessionDirectory INSTANCE = new ResearchBotSessionDirectory();

    /** Registration order = the order sessions were started; stable for a client reading sessions_list. */
    private final Map<String, ResearchBotSessionRegistration> sessions =
            new LinkedHashMap<String, ResearchBotSessionRegistration>();

    /** The host's read-only chat catalog (titles + the selected chat), or null before a host provided it. */
    private volatile ChatSessionCatalog catalog;

    private ResearchBotSessionDirectory() {
    }

    public static ResearchBotSessionDirectory get() {
        return INSTANCE;
    }

    /**
     * The host catalog that resolves titles and the SELECTED chat. Published once per productive start;
     * a null argument is ignored so a lenient host lookup never erases a working catalog.
     */
    public void setChatSessionCatalog(ChatSessionCatalog chatSessionCatalog) {
        if (chatSessionCatalog != null) {
            this.catalog = chatSessionCatalog;
        }
    }

    /**
     * Register a live session. A previous registration under the same public id (a chat reopened after its
     * predecessor's close was slow) is replaced.
     */
    public synchronized ResearchBotSessionRegistration register(String publicSessionId,
                                                                String internalSessionKey,
                                                                ResearchBotSessionGateway gateway) {
        if (publicSessionId == null || publicSessionId.trim().isEmpty() || gateway == null) {
            return null;
        }
        ResearchBotSessionRegistration registration = new ResearchBotSessionRegistration(
                publicSessionId.trim(), internalSessionKey == null ? "" : internalSessionKey, gateway);
        sessions.put(registration.getPublicSessionId(), registration);
        return registration;
    }

    /**
     * Remove EXACTLY this registration. A stale close therefore cannot remove the registration of a session
     * that was started for the same chat in the meantime.
     */
    public synchronized void unregister(ResearchBotSessionRegistration registration) {
        if (registration == null) {
            return;
        }
        ResearchBotSessionRegistration current = sessions.get(registration.getPublicSessionId());
        if (current == registration) {
            sessions.remove(registration.getPublicSessionId());
        }
    }

    /** All live sessions in registration order. */
    public synchronized List<ResearchBotSessionRegistration> list() {
        return new ArrayList<ResearchBotSessionRegistration>(sessions.values());
    }

    /** The live session with this public chat id, or null. */
    public synchronized ResearchBotSessionRegistration find(String publicSessionId) {
        return publicSessionId == null ? null : sessions.get(publicSessionId.trim());
    }

    /** Drop every registration (plugin stop / generation swap). Sessions themselves are closed by the host. */
    public synchronized void clear() {
        sessions.clear();
    }

    /** The chat id currently SELECTED in the UI ("" when unknown) — never "the last session that started". */
    public String currentChatSessionId() {
        ChatSessionCatalog current = catalog;
        if (current == null) {
            return "";
        }
        String id = current.getActiveSessionId();
        return id == null ? "" : id.trim();
    }

    /** The chat's display title, or "" when the host does not know it. */
    public String titleOf(String publicSessionId) {
        ChatSessionCatalog current = catalog;
        if (current == null || publicSessionId == null) {
            return "";
        }
        ChatSessionMetadata metadata = current.getSession(publicSessionId);
        return metadata == null ? "" : metadata.getTitle();
    }

    /**
     * The session an MCP call without an explicit {@code sessionId} addresses: the research session of the
     * chat that is SELECTED in the UI. When the catalog cannot answer (no host catalog yet) and exactly ONE
     * session is live, that unambiguous one is used — with several live sessions the caller must name one,
     * so a tool call can never silently hit the wrong chat.
     */
    public synchronized ResearchBotSessionRegistration currentSession() {
        String activeChat = currentChatSessionId();
        if (!activeChat.isEmpty()) {
            ResearchBotSessionRegistration registration = sessions.get(activeChat);
            if (registration != null) {
                return registration;
            }
            return null; // the selected chat runs no research session — never fall back to another chat
        }
        return sessions.size() == 1 ? sessions.values().iterator().next() : null;
    }
}
