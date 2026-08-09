package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.acp.AcpResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.SourceAcceptanceService;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;
import com.aresstack.askai.research.state.oo.ResearchStateTransitionResult;

/**
 * Everything one PRODUCTIVE research session owns, with exactly one owner per resource:
 * <ul>
 * <li>the ACP research agent process — owned by the {@link AcpResearchSessionBackend} session handle,</li>
 * <li>the research-control MCP endpoint — owned here ({@link ResearchControlEndpoint}),</li>
 * <li>the browser MCP sidecar process (+ its host-side client and bridge endpoint) — owned here,</li>
 * <li>the backend session — owned by whoever called {@code createSession} on the backend port.</li>
 * </ul>
 * The host-side hierarchical state machine held here is the ONLY transition authority: {@link #dispatch}
 * applies a command and immediately republishes the state-derived tool set (tools/list_changed). The agent
 * signals PHASE_READY as an event; the HOST decides by dispatching {@code REQUEST_EVIDENCE_REVIEW}.
 *
 * <p>{@link #close()} is idempotent and ordered: backend (agent process) → research-control endpoint →
 * browser bridge endpoint → sidecar client → sidecar process. Stores/repositories are not "closed" — their
 * contents outlive the session.</p>
 */
public final class ProductiveResearchSessionResources {

    private final String sessionKey;
    private final OoResearchStateMachine stateMachine;
    private volatile ResearchStateMemento state;
    private final CaptureStore captures;
    private final ResearchSourceRepository repository;
    private final SourceAcceptanceService acceptance;
    /** The ONE persistent project context: artifacts, sources, state and metadata on disk. */
    private final com.aresstack.askai.research.store.ResearchProjectContext projectContext;
    private final ResearchControlEndpoint controlEndpoint;
    /** Internal service endpoint (manual_source_accept) for user-triggered searches; own its lifecycle here. */
    private final com.aresstack.askai.research.mcp.ResearchServiceEndpoint serviceEndpoint;
    private final BrowserBridgeEndpoint browserBridge;
    /** The LAZY, restartable browser runtime (STOPPED until the first browser command); owned here. */
    private final BrowserRuntimePort browser;
    private final AcpResearchSessionBackend backend;
    /** The session's immutable settings snapshot (A2c); set once by the factory right after creation. */
    private volatile com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot searchProfile;
    /** The continuous knowledge worker (persistent FIFO drainer), or null when the capability is unavailable. */
    private volatile com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingRunner knowledgeRunner;
    /** The debounced live-projection worker (C5), or null when the knowledge capability is unavailable. */
    private volatile com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner
            projectionRunner;
    private volatile boolean closed;

    ProductiveResearchSessionResources(String sessionKey, OoResearchStateMachine stateMachine,
                                       CaptureStore captures, ResearchSourceRepository repository,
                                       SourceAcceptanceService acceptance,
                                       com.aresstack.askai.research.store.ResearchProjectContext
                                               projectContext,
                                       ResearchControlEndpoint controlEndpoint,
                                       BrowserBridgeEndpoint browserBridge, BrowserRuntimePort browser,
                                       AcpResearchSessionBackend backend) {
        this(sessionKey, stateMachine, captures, repository, acceptance, projectContext, controlEndpoint,
                browserBridge, browser, backend, null);
    }

    ProductiveResearchSessionResources(String sessionKey, OoResearchStateMachine stateMachine,
                                       CaptureStore captures, ResearchSourceRepository repository,
                                       SourceAcceptanceService acceptance,
                                       com.aresstack.askai.research.store.ResearchProjectContext
                                               projectContext,
                                       ResearchControlEndpoint controlEndpoint,
                                       BrowserBridgeEndpoint browserBridge, BrowserRuntimePort browser,
                                       AcpResearchSessionBackend backend,
                                       com.aresstack.askai.research.mcp.ResearchServiceEndpoint
                                               serviceEndpoint) {
        this.sessionKey = sessionKey;
        this.stateMachine = stateMachine;
        this.projectContext = projectContext;
        // RESTORE-or-init: a stored memento wins; without one the initial state is persisted
        // immediately so the project directory always carries the truth from revision 0 on.
        ResearchStateMemento restored = projectContext.getSessionStateStore().load();
        if (restored != null) {
            this.state = restored;
        } else {
            this.state = stateMachine.initialMemento();
            try {
                projectContext.getSessionStateStore().save(this.state);
            } catch (java.io.IOException persistFailed) {
                // FAIL-CLOSED: a productive context whose state store cannot write never
                // activates - the factory's rollback tears everything down.
                throw new IllegalStateException("The research project state store is not "
                        + "writable (" + persistFailed.getMessage() + ") - the productive "
                        + "session must not start", persistFailed);
            }
        }
        this.captures = captures;
        this.repository = repository;
        this.acceptance = acceptance;
        this.controlEndpoint = controlEndpoint;
        this.serviceEndpoint = serviceEndpoint;
        this.browserBridge = browserBridge;
        this.browser = browser;
        this.backend = backend;
    }

    /** The persistent project context this session works on (single source of truth). */
    public com.aresstack.askai.research.store.ResearchProjectContext getProjectContext() {
        return projectContext;
    }

    void setSearchProfile(com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot profile) {
        this.searchProfile = profile;
    }

    /** The continuous knowledge worker owned by this session (stopped in {@link #close()}); may be null. */
    void setKnowledgeRunner(
            com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingRunner runner) {
        this.knowledgeRunner = runner;
    }

    public com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingRunner getKnowledgeRunner() {
        return knowledgeRunner;
    }

    /** The live-projection runner owned by this session (stopped in {@link #close()}); may be null. */
    void setProjectionRunner(
            com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner runner) {
        this.projectionRunner = runner;
    }

    /** Cheap deterministic outline-staleness metadata, or null when the knowledge capability is unavailable. */
    private volatile KnowledgeProcessingSessionFactory.OutlineStalenessCheck outlineStaleness;

    void setOutlineStaleness(KnowledgeProcessingSessionFactory.OutlineStalenessCheck staleness) {
        this.outlineStaleness = staleness;
    }

    /**
     * EXPLICIT user action (issue #29): trigger ONE debounced topic-discovery + outline rebuild off this
     * thread. This is the only rebuild trigger left — nothing invalidates the projection automatically.
     * @return false when the knowledge capability is unavailable (no embedding world).
     */
    public boolean triggerOutlineRebuild() {
        com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner runner =
                projectionRunner;
        if (runner == null) {
            return false;
        }
        runner.knowledgeChanged();
        return true;
    }

    /**
     * Whether the persisted outline is stale relative to the ACTIVE corpus (new passages, Save/Exclude/⭐),
     * or {@code null} when the knowledge capability is unavailable. Pure read, never a rebuild.
     */
    public Boolean isOutlineStale() {
        KnowledgeProcessingSessionFactory.OutlineStalenessCheck check = outlineStaleness;
        if (check == null) {
            return null;
        }
        try {
            return Boolean.valueOf(check.isStale());
        } catch (RuntimeException unreadable) {
            return null; // an unreadable corpus/projection must never break the view
        }
    }

    /** Whether a persisted outline projection exists at all; {@code null} when the capability is unavailable. */
    public Boolean hasPersistedOutline() {
        KnowledgeProcessingSessionFactory.OutlineStalenessCheck check = outlineStaleness;
        if (check == null) {
            return null;
        }
        try {
            return Boolean.valueOf(check.hasPersistedProjection());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Set by the SESSION (issue #33): the one implementation of the explicit derived-action commands. The
     * internal service-MCP endpoint reads it at call time; before the session attached it, actions are
     * honestly unavailable. Never exposed on the agent-facing control endpoint.
     */
    private volatile com.aresstack.askai.research.agent.ResearchDerivedActions derivedActions;

    public void setDerivedActions(com.aresstack.askai.research.agent.ResearchDerivedActions actions) {
        this.derivedActions = actions;
    }

    /** The session's derived-action commands, or {@code null} while no session is attached. */
    public com.aresstack.askai.research.agent.ResearchDerivedActions getDerivedActions() {
        return derivedActions;
    }

    /** The bot-control endpoint (run_command/session_state/chat_history); owned + closed here. */
    private volatile com.aresstack.askai.research.mcp.ResearchBotControlEndpoint botControlEndpoint;

    void setBotControlEndpoint(com.aresstack.askai.research.mcp.ResearchBotControlEndpoint endpoint) {
        this.botControlEndpoint = endpoint;
    }

    /** The session's bot-control endpoint, or {@code null} (disabled by configuration / not attached). */
    public com.aresstack.askai.research.mcp.ResearchBotControlEndpoint getBotControlEndpoint() {
        return botControlEndpoint;
    }

    /** Set by the SESSION: the structured command/state gateway for the bot-control MCP. */
    private volatile com.aresstack.askai.research.mcp.ResearchBotControlEndpoint.SessionGateway sessionGateway;

    public void setSessionGateway(
            com.aresstack.askai.research.mcp.ResearchBotControlEndpoint.SessionGateway gateway) {
        this.sessionGateway = gateway;
    }

    /** The session's gateway, or {@code null} while no session is attached. */
    public com.aresstack.askai.research.mcp.ResearchBotControlEndpoint.SessionGateway getSessionGateway() {
        return sessionGateway;
    }

    /** Set by the SESSION: notified (worker thread) after every persisted live-projection rebuild. */
    private volatile Runnable projectionUpdateListener;

    public void setProjectionUpdateListener(Runnable listener) {
        this.projectionUpdateListener = listener;
    }

    void fireProjectionUpdated() {
        Runnable listener = projectionUpdateListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (RuntimeException never) {
                // a UI refresh failure must never disturb the projection worker
            }
        }
    }

    /** The settings snapshot this RUNNING session uses (global changes only affect NEW sessions). */
    public com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot getSearchProfile() {
        return searchProfile;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    /** The productive backend port the UI session talks to. */
    public ResearchSessionBackend getBackend() {
        return backend;
    }

    public ResearchStateMemento currentState() {
        return state;
    }

    public CaptureStore getCaptures() {
        return captures;
    }

    public ResearchSourceRepository getRepository() {
        return repository;
    }

    public AgentArtifactStore getArtifactStore() {
        return projectContext.getArtifactStore();
    }

    public ResearchControlEndpoint getControlEndpoint() {
        return controlEndpoint;
    }

    public BrowserBridgeEndpoint getBrowserBridge() {
        return browserBridge;
    }

    /** The lazy, restartable browser runtime (STOPPED until the first browser command). */
    public BrowserRuntimePort getBrowser() {
        return browser;
    }

    /**
     * End the current browsing phase: stop the sidecar/browser but keep the TeamAgent and session alive. A
     * later research run lazily starts a fresh browser generation. Called when a research run finishes.
     */
    public void stopBrowserPhase() {
        if (browser != null) {
            browser.stop();
        }
    }

    /**
     * Apply a host/user command to the state machine (the single authority) and republish the tool set.
     * @return the transition result; a rejection leaves state and tools untouched.
     */
    /** Off-EDT executor for tool republication: pushing tools/list_changed writes to a connected (and
     * possibly busy) agent over the network must NEVER run on the Swing EDT — a non-reading peer would
     * freeze the whole UI. Authorization is re-checked server-side at call time, so the tool LIST being
     * eventually consistent is safe. */
    private final java.util.concurrent.ExecutorService toolRefreshExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "research-tool-refresh");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    public synchronized ResearchStateTransitionResult dispatch(ResearchCommandType command) {
        ResearchStateTransitionResult result = stateMachine.dispatch(state,
                ResearchCommand.of(command, "cmd-" + System.nanoTime()));
        if (result.isAccepted()) {
            // PERSIST-BEFORE-APPLY: the new memento must be on disk before anyone can observe it.
            // If persistence fails there is NO success result, NO tool refresh and the active
            // state stays the previous memento — a state never advances only in RAM.
            try {
                projectContext.getSessionStateStore().save(result.getNextMemento());
            } catch (java.io.IOException persistFailed) {
                return ResearchStateTransitionResult.rejected(state,
                        "state transition not persisted (" + persistFailed.getMessage()
                                + ") — the previous state stays active");
            }
            state = result.getNextMemento();
            toolRefreshExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        controlEndpoint.refreshTools();
                    } catch (RuntimeException ignored) {
                        // a failed republication must never break the accepted transition
                    }
                }
            });
        }
        return result;
    }

    /** The live-state view the research-control tool handlers authorize against. */
    ResearchControlContext controlContext() {
        return new ResearchControlContext() {
            public String currentPhaseId() {
                return state.getPhaseId();
            }

            public String currentStateId() {
                return state.getStateId();
            }

            public String statusLine() {
                // Publish the live allowed workflow commands too, so the model-backed TeamAgent (in the
                // runtime process) can propose only a legal next step. The host stays the authority: it
                // re-validates any proposal against this same state machine before executing it.
                StringBuilder line = new StringBuilder(state.getPhaseId()).append('/')
                        .append(state.getStateId()).append(" rev=").append(state.getRevision());
                java.util.Set<com.aresstack.askai.research.state.ResearchCommandType> allowed =
                        com.aresstack.askai.research.state.oo.ResearchStateFactory.getInstance()
                                .restore(state).getCurrentState().getAllowedCommands();
                if (!allowed.isEmpty()) {
                    line.append(" cmds=");
                    boolean first = true;
                    for (com.aresstack.askai.research.state.ResearchCommandType command : allowed) {
                        if (!first) {
                            line.append(',');
                        }
                        first = false;
                        line.append(command.name());
                    }
                }
                return line.toString();
            }

            public AgentArtifactStore artifactStore() {
                return projectContext.getArtifactStore();
            }

            public ResearchSourceRepository sourceRepository() {
                return repository;
            }

            public String acceptCapture(String captureId) {
                return acceptCapture(captureId, "");
            }

            @Override
            public String acceptCapture(String captureId, String searchQuery) {
                return acceptCapture(captureId, searchQuery, false);
            }

            @Override
            public String acceptCapture(String captureId, String searchQuery, boolean userRelevant) {
                return acceptCapture(captureId, searchQuery, userRelevant, "");
            }

            @Override
            public String acceptCapture(String captureId, String searchQuery, boolean userRelevant,
                                        String languageCode) {
                SourceAcceptanceService.Result result =
                        acceptance.accept(captureId, searchQuery, userRelevant, languageCode);
                return result.status == SourceAcceptanceService.Status.UNKNOWN_CAPTURE
                        ? null : result.render();
            }

            @Override
            public String parkCandidate(String url, String title, String excerpt, double rerankScore,
                                        String searchQuery) {
                return acceptance.park(url, title, excerpt, rerankScore, searchQuery).render();
            }
        };
    }

    public boolean isClosed() {
        return closed;
    }

    /** Documented shutdown order; idempotent; safe on partial construction (nulls skipped). */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        toolRefreshExecutor.shutdownNow();
        // Stop the knowledge worker FIRST (graceful: lets the current job finish). The persistent FIFO and the
        // canonical corpus outlive the session, so a mid-flight job is simply recovered on the next open.
        if (projectionRunner != null) {
            projectionRunner.stop();
        }
        if (knowledgeRunner != null) {
            knowledgeRunner.stop();
        }
        if (backend != null) {
            backend.closeAllSessions();
        }
        if (controlEndpoint != null) {
            controlEndpoint.close();
        }
        if (serviceEndpoint != null) {
            serviceEndpoint.close();
        }
        if (botControlEndpoint != null) {
            botControlEndpoint.close();
        }
        if (browserBridge != null) {
            browserBridge.close();
        }
        if (browser != null) {
            browser.close(); // stops any running sidecar generation and releases the owner thread
        }
    }
}
