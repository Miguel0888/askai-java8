package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.McpToolClient;
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
    private final AgentArtifactStore artifactStore;
    private final ResearchControlEndpoint controlEndpoint;
    private final BrowserBridgeEndpoint browserBridge;
    private final McpToolClient sidecarClient;
    private final BrowserMcpSidecarProcess sidecar;
    private final AcpResearchSessionBackend backend;
    /** The session's immutable settings snapshot (A2c); set once by the factory right after creation. */
    private volatile com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot searchProfile;
    private volatile boolean closed;

    ProductiveResearchSessionResources(String sessionKey, OoResearchStateMachine stateMachine,
                                       CaptureStore captures, ResearchSourceRepository repository,
                                       SourceAcceptanceService acceptance, AgentArtifactStore artifactStore,
                                       ResearchControlEndpoint controlEndpoint,
                                       BrowserBridgeEndpoint browserBridge, McpToolClient sidecarClient,
                                       BrowserMcpSidecarProcess sidecar, AcpResearchSessionBackend backend) {
        this.sessionKey = sessionKey;
        this.stateMachine = stateMachine;
        this.state = stateMachine.initialMemento();
        this.captures = captures;
        this.repository = repository;
        this.acceptance = acceptance;
        this.artifactStore = artifactStore;
        this.controlEndpoint = controlEndpoint;
        this.browserBridge = browserBridge;
        this.sidecarClient = sidecarClient;
        this.sidecar = sidecar;
        this.backend = backend;
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
        return artifactStore;
    }

    public ResearchControlEndpoint getControlEndpoint() {
        return controlEndpoint;
    }

    public BrowserBridgeEndpoint getBrowserBridge() {
        return browserBridge;
    }

    public BrowserMcpSidecarProcess getSidecar() {
        return sidecar;
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
                return state.getPhaseId() + "/" + state.getStateId() + " rev=" + state.getRevision();
            }

            public AgentArtifactStore artifactStore() {
                return artifactStore;
            }

            public ResearchSourceRepository sourceRepository() {
                return repository;
            }

            public String acceptCapture(String captureId) {
                SourceAcceptanceService.Result result = acceptance.accept(captureId);
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
        if (browserBridge != null) {
            browserBridge.close();
        }
        if (sidecarClient != null) {
            sidecarClient.close();
        }
        if (sidecar != null) {
            sidecar.close();
        }
    }
}
