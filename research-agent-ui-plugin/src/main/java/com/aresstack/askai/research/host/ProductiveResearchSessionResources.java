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
                SourceAcceptanceService.Result result = acceptance.accept(captureId, searchQuery);
                return result.status == SourceAcceptanceService.Status.UNKNOWN_CAPTURE
                        ? null : result.render();
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
        if (backend != null) {
            backend.closeAllSessions();
        }
        if (controlEndpoint != null) {
            controlEndpoint.close();
        }
        if (serviceEndpoint != null) {
            serviceEndpoint.close();
        }
        if (browserBridge != null) {
            browserBridge.close();
        }
        if (browser != null) {
            browser.close(); // stops any running sidecar generation and releases the owner thread
        }
    }
}
