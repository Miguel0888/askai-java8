package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the agent sessions behind the shared chat and is the {@link ChatSubmissionRouter} the composer talks to.
 * It is deliberately Swing-free so the route matrix and lifecycle are unit-testable without a UI.
 *
 * <p>Invariant: exactly one {@link AgentSession} per agent id (created lazily, reused across mode switches).
 * Switching to Yapping only deactivates the active session; it is not closed, so returning to Questing reuses
 * it. A session is closed only when its plugin is disabled/removed, or on shutdown. The active session is the
 * single routing target; {@code null} means "route to Yapping / no agent".</p>
 */
public final class AgentSessionCoordinator implements ChatSubmissionRouter {

    /** Resolves an agent id to its (selectable) extension, or {@code null} if not available. */
    public interface AgentExtensionResolver {
        AgentPluginExtension resolve(String agentId);
    }

    /** Builds a scoped host context (conversation sink + services) for a new session. */
    public interface AgentHostContextProvider {
        AgentHostContext create(String agentId, String sessionInstanceId);
    }

    private final AgentExtensionResolver resolver;
    private final AgentHostContextProvider hostContextProvider;
    private final Map<String, AgentSession> sessions = new LinkedHashMap<String, AgentSession>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();

    private String activeAgentId;
    private AgentSession activeSession;

    public AgentSessionCoordinator(AgentExtensionResolver resolver,
                                   AgentHostContextProvider hostContextProvider) {
        this.resolver = resolver;
        this.hostContextProvider = hostContextProvider;
    }

    /** @return whether an agent id currently resolves to a usable agent extension. */
    public boolean canHandle(String agentId) {
        return resolver.resolve(agentId) != null;
    }

    /**
     * Make {@code agentId} the active routing target, creating its session on first use and reactivating an
     * existing one otherwise. The previously active session (if different) is deactivated but kept. A null or
     * unresolvable id deactivates the current session and leaves no active target.
     */
    public void setActiveAgent(String agentId) {
        if (agentId == null) {
            deactivateActive();
            return;
        }
        AgentPluginExtension extension = resolver.resolve(agentId);
        if (extension == null) {
            deactivateActive();
            return;
        }
        if (agentId.equals(activeAgentId) && activeSession != null) {
            return; // already active; nothing to switch
        }
        if (activeSession != null) {
            activeSession.deactivate();
        }
        AgentSession session = sessions.get(agentId);
        if (session == null) {
            String sessionInstanceId = agentId + "#session";
            AgentHostContext host = hostContextProvider.create(agentId, sessionInstanceId);
            session = extension.getSessionFactory().create(
                    new AgentSessionCreationRequest(sessionInstanceId, "", null), host);
            sessions.put(agentId, session);
        }
        session.activate();
        activeAgentId = agentId;
        activeSession = session;
        fireChange();
    }

    /** Deactivate the active session (kept for reuse) and route back to Yapping / no agent. */
    public void deactivateActive() {
        if (activeSession != null) {
            activeSession.deactivate();
        }
        activeSession = null;
        activeAgentId = null;
        fireChange();
    }

    /** Close and forget one agent's session (plugin disabled/removed). Falls back if it was active. */
    public void closeAgent(String agentId) {
        AgentSession session = sessions.remove(agentId);
        if (session != null) {
            if (session == activeSession) {
                activeSession = null;
                activeAgentId = null;
            }
            session.close();
            fireChange();
        }
    }

    /**
     * Close every session whose agent id is not in {@code keepIds} (e.g. after a catalog refresh removed a
     * plugin). Sessions still present stay open and, if active, keep routing.
     */
    public void retainOnly(Collection<String> keepIds) {
        List<String> toClose = new ArrayList<String>();
        for (String id : sessions.keySet()) {
            if (keepIds == null || !keepIds.contains(id)) {
                toClose.add(id);
            }
        }
        for (String id : toClose) {
            closeAgent(id);
        }
    }

    /** Close all sessions (process shutdown). */
    public void shutdown() {
        activeSession = null;
        activeAgentId = null;
        for (AgentSession session : sessions.values()) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        sessions.clear();
    }

    public String getActiveAgentId() {
        return activeAgentId;
    }

    // ------------------------------------------------------------------ ChatSubmissionRouter

    @Override
    public boolean isActive() {
        return activeSession != null;
    }

    @Override
    public SubmissionAvailability getAvailability() {
        if (activeSession == null) {
            return SubmissionAvailability.UNAVAILABLE;
        }
        SubmissionAvailability availability = activeSession.getChatTarget().getAvailability();
        return availability == null ? SubmissionAvailability.UNAVAILABLE : availability;
    }

    @Override
    public void submitText(String text) {
        if (activeSession != null) {
            activeSession.getChatTarget().submitText(text);
        }
    }

    @Override
    public void stop() {
        if (activeSession != null) {
            activeSession.getChatTarget().stop();
        }
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Public so the host can nudge the composer to re-read availability as a run progresses. */
    public void fireChange() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
