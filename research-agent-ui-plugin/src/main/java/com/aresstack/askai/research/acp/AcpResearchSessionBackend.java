package com.aresstack.askai.research.acp;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpEndpointDescriptor;
import com.aresstack.askai.acp.AcpException;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.acp.PromptHandle;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ACP-backed {@link ResearchSessionBackend}: a pure ADAPTER that translates start/prompt/cancel/events between
 * the research port and the neutral ACP client API. It owns NO research logic — the state machine, project
 * store and research-control endpoint stay in the plugin session; the external agent orchestrates its run and
 * can never approve or switch phases (there is no such tool). Endpoint descriptors travel as structured
 * launch environment (never prompt text); tokens are never emitted into events or logs.
 *
 * <p>MVP restart policy: if the agent process dies, the session reports FAILED and stays down — no automatic
 * restart loop; the user restarts explicitly and the project store is untouched. The port stays free of
 * ACP-SDK/Reactor types (only the neutral :acp-client-api appears here, compileOnly/host-provided).</p>
 */
public final class AcpResearchSessionBackend implements ResearchSessionBackend {

    private final AcpAgentConnector connector;
    private final AgentLaunchSpec baseSpec;
    private final AcpEndpointDescriptor researchEndpoint;
    private final AcpEndpointDescriptor browserEndpoint; // null => honestly absent (MCP-P005), never faked
    private final Map<String, AcpBackedSession> sessions = new ConcurrentHashMap<String, AcpBackedSession>();

    public AcpResearchSessionBackend(AcpAgentConnector connector, AgentLaunchSpec baseSpec,
                                     AcpEndpointDescriptor researchEndpoint,
                                     AcpEndpointDescriptor browserEndpoint) {
        this.connector = connector;
        this.baseSpec = baseSpec;
        this.researchEndpoint = researchEndpoint;
        this.browserEndpoint = browserEndpoint;
    }

    @Override
    public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                               ResearchSessionListener listener) {
        AcpBackedSession session = new AcpBackedSession(request, listener);
        sessions.put(request.getSessionId(), session);
        session.start();
        return session;
    }

    @Override
    public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
        return false; // functional transitions stay with the plugin's own state machine, not the ACP adapter
    }

    @Override
    public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
        // Intentionally empty: the adapter never drives the state machine (no second research logic).
    }

    @Override
    public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        AcpBackedSession session = resolve(handle);
        if (session != null) {
            session.prompt(prompt.getText());
        }
    }

    @Override
    public void approve(ResearchSessionHandle handle, String approvalId) {
        // Approval is a user/state-machine concern handled by the plugin session, not the agent.
    }

    @Override
    public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        // See approve().
    }

    @Override
    public void pause(ResearchSessionHandle handle) {
        cancel(handle); // MVP: pause of an agent turn = cancel the running prompt (process keeps running)
    }

    @Override
    public void resume(ResearchSessionHandle handle) {
        // No-op for the adapter; the user resumes by prompting again.
    }

    @Override
    public void cancel(ResearchSessionHandle handle) {
        AcpBackedSession session = resolve(handle);
        if (session != null) {
            session.cancelActivePrompt();
        }
    }

    @Override
    public void close(ResearchSessionHandle handle) {
        AcpBackedSession session = handle == null ? null : sessions.remove(handle.getSessionId());
        if (session != null) {
            session.shutdown();
        }
    }

    private AcpBackedSession resolve(ResearchSessionHandle handle) {
        return handle == null ? null : sessions.get(handle.getSessionId());
    }

    // ------------------------------------------------------------------ session

    private final class AcpBackedSession implements ResearchSessionHandle {
        private final ResearchProjectRequest request;
        private final ResearchSessionListener listener;
        private final AtomicLong sequence = new AtomicLong();
        private volatile AcpConnection connection;
        private volatile AcpSession acpSession;
        private volatile PromptHandle activePrompt;
        private volatile boolean closed;

        private AcpBackedSession(ResearchProjectRequest request, ResearchSessionListener listener) {
            this.request = request;
            this.listener = listener;
        }

        public String getSessionId() {
            return request.getSessionId();
        }

        public String getProjectId() {
            return request.getProjectId();
        }

        /**
         * Atomic start: connect (spawn + initialize) → session/new → hand over endpoints via ENVIRONMENT
         * (structured, never prompt text). Any failure rolls the process back and reports ERROR — no
         * half-started session survives.
         */
        void start() {
            try {
                // Deliberately NOT the host's full environment: only the base spec's explicit vars plus the
                // ASKAI_* contract below — the child never inherits arbitrary host secrets.
                java.util.Map<String, String> env =
                        new java.util.LinkedHashMap<String, String>(baseSpec.getEnv());
                env.put("ASKAI_SESSION_ID", request.getSessionId());
                env.put("ASKAI_PROJECT_ID", request.getProjectId());
                env.put("ASKAI_RESEARCH_MCP_URL", researchEndpoint.getUrl());
                env.put("ASKAI_RESEARCH_MCP_TRANSPORT", researchEndpoint.getTransport());
                putToken(env, "ASKAI_RESEARCH_MCP_TOKEN", researchEndpoint.getToken());
                if (browserEndpoint != null) {
                    // Browser vars are fully absent when no browser backend exists — never empty values.
                    env.put("ASKAI_BROWSER_MCP_URL", browserEndpoint.getUrl());
                    env.put("ASKAI_BROWSER_MCP_TRANSPORT", browserEndpoint.getTransport());
                    putToken(env, "ASKAI_BROWSER_MCP_TOKEN", browserEndpoint.getToken());
                }
                AgentLaunchSpec spec = new AgentLaunchSpec(
                        baseSpec.getCommand(), baseSpec.getArgs(), env);
                connection = connector.connect(spec);
                acpSession = connection.newSession();
                if (browserEndpoint == null) {
                    emit(ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                            .activity("acp-browser", com.aresstack.askai.research.backend
                                    .ResearchActivityKind.TOOL_UPDATE, "Browser",
                                    "BROWSER_NOT_AVAILABLE: no live browser backend; web tools are hidden."));
                }
            } catch (AcpException ex) {
                rollback();
                emit(ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("The research agent could not be started.",
                                ex.getPhase() + ": " + ex.getMessage()));
            }
        }

        void prompt(String text) {
            if (closed || acpSession == null) {
                return;
            }
            activePrompt = acpSession.prompt(text, new AcpUpdateListener() {
                public void onUpdate(AcpUpdate update) {
                    ResearchBackendEvent.Builder builder = ResearchAcpEventMapper.mapUpdate(update);
                    if (builder != null) {
                        emit(builder);
                    }
                }

                public void onTerminal(String promptId, AcpPromptState state, String detail) {
                    ResearchBackendEvent.Builder builder = ResearchAcpEventMapper.mapTerminal(state, detail);
                    if (builder != null) {
                        emit(builder);
                    }
                    activePrompt = null;
                }
            });
        }

        void cancelActivePrompt() {
            PromptHandle prompt = activePrompt;
            if (prompt != null) {
                prompt.cancel(); // cancels the turn only; the process stays alive
            }
        }

        void shutdown() {
            closed = true;
            rollback();
        }

        private void rollback() {
            try {
                if (acpSession != null) {
                    acpSession.close();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            acpSession = null;
            connection = null;
        }

        /** Never write an empty token value; absence must be distinguishable from a blank secret. */
        private void putToken(java.util.Map<String, String> env, String key, String token) {
            if (token != null && !token.trim().isEmpty()) {
                env.put(key, token);
            }
        }

        private void emit(ResearchBackendEvent.Builder builder) {
            if (closed || listener == null) {
                return;
            }
            listener.onEvent(builder.envelope("acp-" + sequence.get(), request.getSessionId(),
                    request.getProjectId(), 0L, 0L, sequence.incrementAndGet(), null).build());
        }
    }
}
