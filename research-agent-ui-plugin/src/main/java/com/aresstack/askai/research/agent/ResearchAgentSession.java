package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.ChatSubmissionTarget;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchScheduler;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

import java.util.ArrayList;
import java.util.List;

/**
 * A research {@link AgentSession} backed by the existing deterministic {@link ResearchSessionBackend}. It owns
 * no chat surface and no composer: backend events are marshalled onto the UI thread via {@link UiExecutor} and
 * pushed into the shared chat through the {@link AgentConversationSink}, with the same late/duplicate/foreign
 * guards the workspace controller uses. Slash commands call the typed control methods here (never raw strings),
 * which reach {@code DefaultResearchStateMachine} through the backend.
 */
public final class ResearchAgentSession implements AgentSession, ResearchSessionListener,
        com.aresstack.askai.research.backend.ResearchSessionCommandPort {

    private final ResearchSessionBackend backend;
    private final ResearchScheduler ownedScheduler;
    private final AgentConversationSink sink;
    private final UiExecutor uiExecutor;
    private final ResearchProjectRequest request;
    private final List<AgentArtifact> artifacts = ResearchArtifacts.all();
    private final AgentArtifactStore artifactStore = new ResearchArtifactStore();
    private final com.aresstack.askai.research.sources.ResearchSourceRepository sourceRepository =
            new com.aresstack.askai.research.sources.InMemoryResearchSourceRepository();
    private final ChatSubmissionTarget chatTarget = new ResearchChatTarget();

    /** This session's OWN live language + the playbook bound to it (never static, never shared). */
    private final SessionResearchLanguage sessionLanguage;
    private final ResearchPlaybook playbook;

    // View-model (updated only on the UI thread from backend events). The hierarchical OO memento is the single
    // source of truth: phase, exact state, precise continuation and the pending approval id all come from it.
    private final com.aresstack.askai.research.state.oo.ResearchStateFactory stateFactory =
            com.aresstack.askai.research.state.oo.ResearchStateFactory.getInstance();
    private com.aresstack.askai.research.state.oo.ResearchStateMemento state =
            stateFactory.snapshot(stateFactory.initialPhase(), 0L);
    private String problemMessage = "";
    private long revision;
    private long lastSequence = -1L;

    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> stateListeners =
            new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    private ResearchSessionHandle handle;
    private boolean started;
    // Volatile: set on close() (possibly off-EDT) and read by late async browser/ACP callbacks on the EDT —
    // a callback that arrives after the tab closed must apply nothing.
    private volatile boolean disposed;
    private final com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostStateStore;
    private final AgentHostContext hostContext;
    /** This session's id — used to unregister from the host active-session registry on close. */
    private final String sessionId;
    /** Productive mode only: the session's OWN generation-scoped resources (state authority + processes). */
    private final com.aresstack.askai.research.host.ProductiveResearchSessionResources productiveResources;

    /**
     * @param ownedScheduler a scheduler this session must shut down on {@link #close()} (the production path),
     *                        or {@code null} when the scheduler is owned elsewhere (tests inject their own).
     */
    public ResearchAgentSession(ResearchSessionBackend backend, ResearchScheduler ownedScheduler,
                                AgentHostContext host, String sessionId, String projectId) {
        this(backend, ownedScheduler, host, sessionId, projectId, null);
    }

    /**
     * Productive constructor: the session OWNS the resources (closed last on {@link #close()}) and routes
     * structured commands to the resources' state machine — the single transition authority.
     */
    public ResearchAgentSession(ResearchSessionBackend backend, ResearchScheduler ownedScheduler,
                                AgentHostContext host, String sessionId, String projectId,
                                com.aresstack.askai.research.host.ProductiveResearchSessionResources resources) {
        this.backend = backend;
        this.ownedScheduler = ownedScheduler;
        this.hostContext = host;
        this.sessionId = sessionId;
        this.sink = host.getConversationSink();
        this.uiExecutor = host.getUiExecutor();
        this.hostStateStore = host.getStateStore();
        // Session-LOCAL language: seeded from the persisted default, then owned by THIS session alone —
        // two parallel research tabs switch independently, nothing is process-global.
        this.sessionLanguage = new SessionResearchLanguage(ResearchLanguage.fromCode(
                com.aresstack.askai.research.host.ResearchRuntimeSettings.loadLanguage(
                        host.getStateStore())));
        this.playbook = new ResearchPlaybook(sessionLanguage);
        this.narrator = new StaticNarrator(playbook);
        this.scoping = new ScopingConversation(narrator);
        this.productiveResources = resources;
        this.request = new ResearchProjectRequest(sessionId, projectId, "Research project");
        if (resources != null) {
            this.state = resources.currentState(); // one truth from the start
            wireBrowserActivity(resources);
            // C5: when the live outline projection was rebuilt (worker thread), let every state listener —
            // including an open Live Outline artifact view — re-read; marshalled like any other refresh.
            resources.setProjectionUpdateListener(new Runnable() {
                public void run() {
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            fireStateChanged();
                        }
                    });
                }
            });
        }
    }

    /** The session's OWN live language — the toolbar switch mutates exactly this, nothing global. */
    public SessionResearchLanguage getSessionLanguage() {
        return sessionLanguage;
    }

    /**
     * Live language switch for THIS session only: host texts and narrations pick it up on the next
     * utterance; already rendered history stays untouched. The runtime agent is synchronised best-effort
     * via a {@code set_language} service command — no chat turn, no history entry, no state-machine
     * command; a fake backend's submitServiceCommand is a no-op.
     */
    public void changeLanguage(ResearchLanguage value) {
        sessionLanguage.change(value);
        if (handle != null && !disposed) {
            backend.submitServiceCommand(handle,
                    com.aresstack.askai.research.search.ResearchServiceCommandWire.setLanguage(
                            sessionLanguage.currentLanguage().getCode()));
        }
    }

    /**
     * Surface the lazy browser lifecycle as a transient chat activity: a "Starte Browser…" thinking bubble
     * when the sidecar starts on the first browser command, finished with "Browser bereit." on READY (or a
     * visible problem on failure). The runtime fires these on its owner thread; we marshal onto the EDT.
     */
    private void wireBrowserActivity(
            com.aresstack.askai.research.host.ProductiveResearchSessionResources resources) {
        if (resources.getBrowser() == null) {
            return; // fake/unit resources without a browser runtime
        }
        resources.getBrowser().setListener(
                new com.aresstack.askai.research.host.BrowserRuntimePort.Listener() {
                    public void onStarting(long generation) {
                        final String id = "browser-start-" + generation;
                        onUi(new Runnable() {
                            public void run() {
                                if (disposed) {
                                    return; // tab closed while the browser was starting: post nothing
                                }
                                sink.startThinking(id, playbook.browserStarting());
                            }
                        });
                    }

                    public void onReady(final long generation) {
                        onUi(new Runnable() {
                            public void run() {
                                if (disposed) {
                                    return; // a late READY after close must not post into a closed sink
                                }
                                sink.finishThinking("browser-start-" + generation,
                                        playbook.browserReady());
                            }
                        });
                    }

                    public void onFailed(final long generation, final String detail) {
                        onUi(new Runnable() {
                            public void run() {
                                if (disposed) {
                                    return; // a late failure after close applies nothing
                                }
                                sink.finishThinking("browser-start-" + generation, "");
                                sink.showProblem("browser-start-" + generation,
                                        playbook.browserFailed(detail));
                            }
                        });
                    }

                    public void onStopped(long generation) {
                        // Nothing to show: the run outcome card already tells the user the run ended.
                    }
                });
    }

    private void onUi(Runnable runnable) {
        if (uiExecutor != null) {
            uiExecutor.execute(runnable);
        } else {
            runnable.run();
        }
    }

    /** Plugin-internal: the host's persisted state store (used by the runtime settings view). */
    /** The immutable settings snapshot of THIS session, or null (fake backend / not started). */
    public com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot getActiveSearchProfile() {
        return productiveResources == null ? null : productiveResources.getSearchProfile();
    }

    public com.aresstack.askai.plugin.api.service.WorkspaceStateStore getHostStateStore() {
        return hostStateStore;
    }

    /** Plugin-internal: a host runtime service (e.g. the reranker model catalog), or null. */
    public <T> T getHostService(Class<T> type) {
        return hostContext == null ? null : hostContext.getService(type);
    }

    // ------------------------------------------------------------------ AgentSession lifecycle

    /** Visible one-time message shown when the session starts (e.g. the demo-mode notice). */
    private volatile String startupNotice;

    public void setStartupNotice(String notice) {
        this.startupNotice = notice;
    }

    @Override
    public void activate() {
        if (disposed || started) {
            return;
        }
        // Mark started BEFORE createSession: the backend emits the initial START event synchronously, so the
        // listener must already accept it even though the handle field is assigned only when the call returns.
        started = true;
        handle = backend.createSession(request, this);
        // Wire the user web search onto the productive backend transport (a #RSC1# service command over ACP).
        // Transport-agnostic: a fake/clickdummy backend's submitServiceCommand is a no-op. Tests may override
        // the port AFTER activate().
        this.manualWebSearchPort = new com.aresstack.askai.research.search.BackendManualWebSearchPort(
                backend, handle);
        restoreManualSearchedQueries(); // covered queries survive a restart (each source remembers its query)
        final String notice = startupNotice;
        if (notice != null && sink != null) {
            uiExecutor.execute(new Runnable() {
                public void run() {
                    sink.showProblem("research-runtime-mode", notice);
                }
            });
        }
        if (productiveResources != null && problemMessage.isEmpty()) {
            // Still restore the persisted assignment (question + focus areas) so a resumed project keeps its
            // scope for continuation; the scoping ceremony is no longer host-driven.
            restoreProjectMetadata();
            // The greeting depends ONLY on the state: greet exactly once, when the scope state is still fresh
            // (SCOPING/NEW). A restored session whose state already advanced past NEW is NOT greeted again —
            // its prior greeting comes back from the persisted chat transcript instead (no double greeting).
            com.aresstack.askai.research.state.oo.ResearchStateMemento current =
                    productiveResources.currentState();
            boolean freshState =
                    com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(current.getPhaseId())
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.NEW.equals(current.getStateId());
            if (freshState) {
                // The model-backed TeamAgent (runtime process) OWNS the greeting: send ONE bootstrap turn so
                // its model-generated greeting arrives as an assistant message. On success the runtime signals
                // GREETING_DONE and the host advances the state one step (see handleGreetingDone).
                beginAgentTurn(); // busy + preempt visualizer; cleared by the greeting turn's terminal event
                backend.submitPrompt(handle, new ResearchPrompt("", ""));
            } else {
                // Restored session: the conversation text comes back from the persisted transcript, but the
                // interactive buttons do not. Re-derive the decision buttons from the live state
                // (non-persisted, so they never duplicate/accumulate across restarts), and bring back the
                // display-only scoping tags (persisted separately; the runtime does not re-emit them).
                restoreScopingProjection();
                showRestoredActionsIfAny();
            }
        }
    }

    /** Seam for restored decision buttons; the event-bus card model (issue #13) replaces this later. */
    private final RestoredActionsProvider restoredActionsProvider = new AllowedCommandsActionsProvider();

    /**
     * On restore, re-show the decision buttons re-derived from the LIVE state through
     * {@link RestoredActionsProvider} (a compact, NON-persisted card — the content is already in the
     * restored transcript). States without a user decision (running, terminal) show nothing. Covers every
     * decision state: approval gates, ready-to-start waits, paused/blocked/failed interruptions.
     */
    private void showRestoredActionsIfAny() {
        if (productiveResources == null || sink == null) {
            return;
        }
        final com.aresstack.askai.research.state.oo.ResearchStateMemento current =
                productiveResources.currentState();
        final java.util.List<RestoredActionsProvider.RestoredAction> actions =
                restoredActionsProvider.deriveFrom(stateFactory.restore(current));
        if (actions.isEmpty()) {
            return;
        }
        uiExecutor.execute(new Runnable() {
            public void run() {
                java.util.List<AgentConversationSink.ActionOption> options =
                        new ArrayList<AgentConversationSink.ActionOption>();
                for (RestoredActionsProvider.RestoredAction action : actions) {
                    options.add(new AgentConversationSink.ActionOption(action.getActionId(),
                            playbook.actionLabel(action.getActionId())));
                }
                String cardId = current.getPendingApprovalId() == null
                        ? "actions-restored-" + current.getRevision() : current.getPendingApprovalId();
                String text = narrator.describePhase(current.getPhaseId(), current.getStateId(),
                        !researchQuestion.isEmpty());
                sink.showLiveActionCard(cardId, text, options,
                        new AgentConversationSink.ActionHandler() {
                            public AgentConversationSink.ActionExecutionResult onAction(String actionId) {
                                return applyRestoredAction(actions, actionId);
                            }
                        });
            }
        });
    }

    /** Applies a restored button press; unknown/no-longer-allowed actions reject and keep the card. */
    private AgentConversationSink.ActionExecutionResult applyRestoredAction(
            java.util.List<RestoredActionsProvider.RestoredAction> actions, String actionId) {
        for (RestoredActionsProvider.RestoredAction action : actions) {
            if (action.getActionId().equals(actionId)) {
                if ("approve".equals(actionId)) {
                    approveCurrent();
                } else if ("changes".equals(actionId)) {
                    requestChanges("");
                    narrateAsAgent("refine", narrator.refinePrompt());
                } else {
                    if (!dispatch(action.getCommand(), null).isAccepted()) {
                        return AgentConversationSink.ActionExecutionResult.REJECTED;
                    }
                    maybeStartResearchTurn();
                }
                return AgentConversationSink.ActionExecutionResult.ACCEPTED;
            }
        }
        return AgentConversationSink.ActionExecutionResult.REJECTED;
    }

    @Override
    public void deactivate() {
        // Keep all state; the run continues in the background.
    }

    @Override
    public void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        briefWriteExecutor.shutdown(); // stop accepting brief writes; in-flight writes are atomic
        if (artifactVisualizer != null) {
            artifactVisualizer.shutdown(); // stop the lazy visualizer; a running visualize is discarded
        }
        // No longer a running session: stop AskAI from re-publishing descriptors to a torn-down dir.
        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry activeSessions =
                hostContext.getService(
                        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry.class);
        if (activeSessions != null) {
            activeSessions.unregister(sessionId);
        }
        if (handle != null) {
            backend.close(handle);
            handle = null;
        }
        invalidateNarration();
        if (narrationScheduler != null) {
            narrationScheduler.shutdown();
        }
        if (ownedScheduler != null) {
            ownedScheduler.shutdown();
        }
        if (productiveResources != null) {
            productiveResources.close(); // endpoints → sidecar client → sidecar process (idempotent)
        }
    }

    @Override
    public ChatSubmissionTarget getChatTarget() {
        return chatTarget;
    }

    @Override
    public List<AgentArtifact> getArtifacts() {
        return artifacts;
    }

    @Override
    public AgentArtifactStore getArtifactStore() {
        // Productive sessions expose the resources' store — the one the agent's MCP endpoint writes to.
        // The session-local store is only the clickdummy/demo world.
        return productiveResources != null ? productiveResources.getArtifactStore() : artifactStore;
    }

    /**
     * Plugin-internal accessor (same classloader): the structured sources repository for the sources view.
     * In productive mode this MUST be the resources' repository (where {@code source_accept} lands) — the
     * session-local in-memory repository only backs the demo mode with its visibly seeded examples.
     */
    public com.aresstack.askai.research.sources.ResearchSourceRepository getSourceRepository() {
        return productiveResources != null ? productiveResources.getRepository() : sourceRepository;
    }

    @Override
    public AgentStateSnapshot getState() {
        ResearchPhase phase = com.aresstack.askai.research.state.oo.ResearchStateIds.phase(state.getPhaseId());
        ResearchRunState run =
                com.aresstack.askai.research.state.oo.ResearchStateIds.runState(state.getStateId());
        boolean busy = com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(state.getStateId())
                || postSearchSummaryInFlight; // the post-search summary keeps the bot "am Zug" (red send)
        return AgentStateSnapshot.builder()
                .phaseLabel(phase.name())
                .runStateLabel(run.name())
                .busy(busy)
                .pendingApproval(state.getPendingApprovalId() != null)
                .pendingApprovalId(state.getPendingApprovalId())
                .revision(revision)
                .statusLine(phase + " / " + run)
                .allowedCommandNames(allowedCommandNames())
                .build();
    }

    // ------------------------------------------------------------------ typed controls (used by slash commands)

    public boolean hasPendingApproval() {
        return state.getPendingApprovalId() != null;
    }

    /** The user's research question (set once scoping is confirmed; auto-continued after approval). */
    private volatile String researchQuestion = "";
    /** The latest scoping assistant projection (search suggestions) for the composer accessory; transient. */
    private volatile com.aresstack.askai.research.backend.ScopingAssistantUpdate latestScopingProjection;
    /** File-backed research brief store, bound to this session's project dir (null in the clickdummy). */
    private com.aresstack.askai.research.store.FileResearchBriefStore researchBriefStore;
    /** USER-triggered web search service; wired to the productive backend transport at activate(). */
    private volatile com.aresstack.askai.research.search.ManualWebSearchPort manualWebSearchPort =
            new com.aresstack.askai.research.search.LoggingManualWebSearchPort();
    /** The in-flight user search's correlation id (events carrying any other id are stale) and its handle. */
    private volatile String activeManualSearchRequestId;
    private volatile com.aresstack.askai.research.search.ManualWebSearchHandle activeManualSearchHandle;
    /** The query of the in-flight user search (remembered so a completed search marks it as covered). */
    private volatile String activeManualSearchQuery;
    /** True from a manual search's browser-close until the bot's summary/new suggestions arrive: shows a
     * thinking bubble AND keeps the composer busy (red send button) so the user sees work is still ongoing. */
    private volatile boolean postSearchSummaryInFlight;
    private volatile String postSearchThinkingId;
    /** Normalized queries a manual search already covered — the agent's suggestions never re-offer these. */
    private final java.util.Set<String> manualSearchedQueries =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Lazy, host-side artifact visualizer (null when no inference port); a derived-view consumer. */
    private com.aresstack.askai.research.visualize.LazyArtifactVisualizer artifactVisualizer;
    /** The latest derived visualization projection for the "Visualisierung" view; transient/rebuildable. */
    private volatile com.aresstack.askai.research.visualize.VisualizationProjection latestVisualization;
    /** The lifecycle status of the visualization (so the view distinguishes never-ran / running / NONE / fail). */
    private volatile com.aresstack.askai.research.visualize.VisualizationStatus visualizationStatus =
            com.aresstack.askai.research.visualize.VisualizationStatus.NOT_STARTED;
    /** Serializes research-brief working-copy writes OFF the EDT (applyEvent runs on the EDT). */
    private final java.util.concurrent.ExecutorService briefWriteExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread thread = new Thread(r, "research-brief-write");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
    /** True while an agent TURN is in flight (productive composer busy-state; cleared on terminal events). */
    private volatile boolean agentTurnInFlight;

    /**
     * A foreground agent turn is starting: mark the composer busy AND preempt the low-priority artifact
     * visualizer so it yields the shared, serial model immediately (its in-flight inference is aborted, its
     * dirty target kept, and it retries the latest artifact once the turn is done). The visualizer is only
     * touched when it already exists — we never create one here just to preempt it.
     */
    private void beginAgentTurn() {
        agentTurnInFlight = true;
        com.aresstack.askai.research.visualize.LazyArtifactVisualizer visualizer = artifactVisualizer;
        if (visualizer != null) {
            visualizer.preempt();
        }
    }
    /** The narration seam: all conversational milestone texts; replaceable by an LLM-backed narrator. */
    private final ResearchNarrator narrator;
    /** The consultative scoping dialog (productive mode). */
    private final ScopingConversation scoping;
    /** Optional warm phrasing (LLM): set once by the factory when the toggle is on and the port exists. */
    private com.aresstack.askai.research.agent.narration.NarrationCoordinator narration;
    private ResearchScheduler narrationScheduler;
    private static final long NARRATION_TIMEOUT_MILLIS = 6000L;

    /**
     * Enable LLM narration: milestone texts go through the coordinator (thought bubble → validated warm
     * text, static fallback on timeout/violation). Without this call every text stays static — identical
     * visible behavior, that is the contract.
     */
    public void configureNarration(com.aresstack.askai.research.agent.narration.AsyncNarrator asyncNarrator) {
        if (asyncNarrator == null || narration != null) {
            return;
        }
        narrationScheduler = new com.aresstack.askai.research.backend.RealResearchScheduler();
        narration = new com.aresstack.askai.research.agent.narration.NarrationCoordinator(
                asyncNarrator, new com.aresstack.askai.research.agent.narration.NarrationValidator(),
                sink, uiExecutor, narrationScheduler, NARRATION_TIMEOUT_MILLIS);
    }

    /** Milestone text through the narration lifecycle; without narration exactly {@link #sayAsAgent}. */
    private void narrateAsAgent(String kind, String fallbackText) {
        if (narration == null) {
            sayAsAgent(fallbackText);
            return;
        }
        narration.narrate(new com.aresstack.askai.research.agent.narration.NarrationRequest(
                        "narration-" + kind + "-" + playbookMessageIds.incrementAndGet(),
                        playbook.narratorThinking(), fallbackText),
                new com.aresstack.askai.research.agent.narration.NarrationCoordinator.Presenter() {
                    public void present(String text) {
                        sayAsAgent(text);
                    }
                });
    }

    /** The situation moved on: in-flight narrations are stale (bubbles close silently, model freed). */
    private void invalidateNarration() {
        if (narration != null) {
            narration.invalidate();
        }
    }

    private final java.util.concurrent.atomic.AtomicLong playbookMessageIds =
            new java.util.concurrent.atomic.AtomicLong();

    public void submitPrompt(String text, String activeSectionId) {
        if (handle == null) {
            return;
        }
        if (productiveResources != null && !productiveResources.isClosed()) {
            // Productive mode: the model-backed TeamAgent LEADS the whole conversation (greeting, scoping,
            // outline proposal, meta questions). Forward the user's text to the runtime agent; its reply
            // returns as an assistant message and any validated proposal returns as a SCOPE_PROPOSAL the host
            // executes. The host no longer runs ScopingConversation or the playbook greeting/outline here —
            // those stay for FAKE mode and the legacy tests only.
            // The productive ACP agent cannot echo the user's OWN message back, so show it in the shared chat
            // here (right-aligned, by role) before the agent replies — otherwise the user's turn is invisible.
            echoUserMessage(text);
            beginAgentTurn(); // busy + preempt visualizer; cleared by the turn's terminal event
            backend.submitPrompt(handle, new ResearchPrompt(text, activeSectionId));
            return;
        }
        // FAKE mode: host-side explainability + the deterministic backend, unchanged. Meta questions are
        // answered from the playbook + live state, in plain language — never with internal identifiers.
        String phaseDescription = narrator.describePhase(state.getPhaseId(), state.getStateId(),
                !scoping.getQuestion().isEmpty());
        String explanation = narrator.explainOrNull(text, phaseDescription);
        if (explanation != null) {
            narrateAsAgent("explain", explanation);
            return;
        }
        backend.submitPrompt(handle, new ResearchPrompt(text, activeSectionId));
    }

    /**
     * Execute a VALIDATED scope proposal from the runtime TeamAgent. The host is the only command authority:
     * a proposal only applies while still SCOPING (else the state moved on and it is dropped), and the commit
     * + {@link #autoAdvanceTowardsResearch()} only issue transitions the state machine actually allows. The
     * concept + outline are built deterministically FROM the model-confirmed scope — no host-side dialogue.
     * The model never confirms its own scope: this is triggered only by the host executing the proposal.
     */
    private void handleScopeProposal(String question, String aspectsBlock) {
        if (productiveResources == null || productiveResources.isClosed() || handle == null) {
            return;
        }
        if (!com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING
                .equals(productiveResources.currentState().getPhaseId())) {
            return; // a scope proposal only applies during scoping; the host state has moved on
        }
        if (question == null || question.trim().isEmpty()) {
            return;
        }
        java.util.List<String> aspects = new ArrayList<String>();
        if (aspectsBlock != null && !aspectsBlock.isEmpty()) {
            for (String part : aspectsBlock.split("\n")) {
                if (!part.trim().isEmpty()) {
                    aspects.add(part.trim());
                }
            }
        }
        ScopingConversation built = new ScopingConversation();
        built.restoreCompleted(question.trim(), aspects);
        ResearchScopeCommitService.ScopeCommitResult commit =
                new ResearchScopeCommitService(productiveResources.getProjectContext())
                        .commit(new ConfirmedResearchScope(question.trim(), aspects,
                                built.buildConceptMarkdown(), built.buildOutlineMarkdown()));
        if (!commit.isSuccess()) {
            sayAsAgent(playbook.scopeCommitFailed(commit.getStatus() + ": " + commit.getDetail()));
            return;
        }
        researchQuestion = question.trim();
        // C5: no outline approval gate anymore — a committed scope auto-advances STRAIGHT into
        // RESEARCH/running, so the stored question must start the research turn HERE (previously the
        // outline-gate approval triggered it).
        autoAdvanceTowardsResearch();
        maybeStartResearchTurn();
    }

    /**
     * The model-backed greeting was delivered successfully: advance the scope state ONE step (SCOPING/NEW →
     * SCOPING/RUNNING via START). The greeting depends only on the state, so after this a restart sees a
     * non-fresh state and never greets again (the persisted chat shows the prior greeting instead). Idempotent
     * and only meaningful while still at SCOPING/NEW.
     */
    private void handleGreetingDone() {
        if (productiveResources == null || productiveResources.isClosed()) {
            return;
        }
        com.aresstack.askai.research.state.oo.ResearchStateMemento current =
                productiveResources.currentState();
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(current.getPhaseId())
                && com.aresstack.askai.research.state.oo.ResearchStateIds.NEW.equals(current.getStateId())
                && currentAllowedCommands().contains(ResearchCommandType.START)) {
            dispatch(ResearchCommandType.START, null);
        }
    }

    /**
     * Show the user's OWN turn in the shared chat (right-aligned, by role) — the productive ACP agent never
     * echoes it back. Empty bootstrap turns carry no text and are ignored, so no blank user bubble appears.
     */
    private void echoUserMessage(final String text) {
        if (sink == null || text == null || text.trim().isEmpty()) {
            return;
        }
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.appendUserMessage("user-" + playbookMessageIds.incrementAndGet(), text);
            }
        });
    }


    /** An agent utterance from the playbook/dialog, routed through the shared sink on the UI thread. */
    private void sayAsAgent(final String text) {
        if (sink == null) {
            return;
        }
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.appendAssistantMessage("playbook-" + playbookMessageIds.incrementAndGet(), text);
            }
        });
    }

    /**
     * Restore the persisted research assignment of this project, if any: the question and the
     * confirmed focus areas come back typed, the scoping dialog is marked complete — a restored
     * session never repeats the scoping ceremony.
     */
    private boolean restoreProjectMetadata() {
        if (productiveResources == null) {
            return false;
        }
        com.aresstack.askai.research.store.MetadataLoadResult loadResult =
                productiveResources.getProjectContext().getMetadataStore()
                        .load(productiveResources.getProjectContext().getProjectId());
        if (loadResult.getStatus()
                != com.aresstack.askai.research.store.MetadataLoadResult.Status.LOADED
                || !loadResult.getMetadata().hasResearchQuestion()) {
            // MISSING is a fresh project; damaged metadata never reaches this point because the
            // factory blocks the productive start fail-closed.
            return false;
        }
        com.aresstack.askai.research.store.ResearchProjectMetadata metadata =
                loadResult.getMetadata();
        researchQuestion = metadata.getResearchQuestion();
        scoping.restoreCompleted(metadata.getResearchQuestion(),
                metadata.getConfirmedFocusAreas());
        return true;
    }

    /**
     * Advance the productive state machine through the transitions that need NO human approval
     * (START, SUBMIT_SCOPE, PROPOSE_OUTLINE, START_RESEARCH). At an approval gate the machine stops and
     * the approval is surfaced in the chat — the user decides; phase rules stay in the machine.
     */
    private void autoAdvanceTowardsResearch() {
        for (int guard = 0; guard < 8; guard++) {
            com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                    productiveResources.currentState();
            String phase = memento.getPhaseId();
            String stateId = memento.getStateId();
            ResearchCommandType next = null;
            if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(phase)
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.NEW.equals(stateId)) {
                next = ResearchCommandType.START;
            } else if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(phase)
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(stateId)) {
                next = ResearchCommandType.SUBMIT_SCOPE;
            } else if (com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH.equals(phase)
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.WAITING.equals(stateId)) {
                next = ResearchCommandType.START_RESEARCH;
            }
            if (next == null) {
                break;
            }
            if (!dispatch(next, null).isAccepted()) {
                break;
            }
        }
        final com.aresstack.askai.research.state.oo.ResearchStateMemento after =
                productiveResources.currentState();
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.WAITING_APPROVAL
                .equals(after.getStateId()) && sink != null) {
            // Phase-neutral gate (C5: scoping no longer routes through an outline approval — the live
            // outline is a mobile projection; this card serves the remaining gates, e.g. evidence).
            final String message = "The " + after.getPhaseId()
                    + " needs your approval.\n\nApprove to continue, or request changes.";
            uiExecutor.execute(new Runnable() {
                public void run() {
                    String approvalId = after.getPendingApprovalId() == null
                            ? "approval-" + after.getRevision() : after.getPendingApprovalId();
                    // Real buttons, no slash ceremony: approve starts the research automatically.
                    java.util.List<AgentConversationSink.ActionOption> options =
                            new ArrayList<AgentConversationSink.ActionOption>();
                    options.add(new AgentConversationSink.ActionOption("approve",
                            playbook.actionLabel("approve")));
                    options.add(new AgentConversationSink.ActionOption("changes",
                            playbook.actionLabel("changes")));
                    sink.showActionCard(approvalId, message, options,
                            new AgentConversationSink.ActionHandler() {
                                public AgentConversationSink.ActionExecutionResult onAction(String actionId) {
                                    if ("approve".equals(actionId)) {
                                        approveCurrent();
                                    } else {
                                        requestChanges("");
                                        narrateAsAgent("refine", narrator.refinePrompt());
                                    }
                                    return AgentConversationSink.ActionExecutionResult.ACCEPTED;
                                }
                            });
                }
            });
        }
    }

    // ------------------------------------------------------------------ ResearchSessionCommandPort

    /** Free-form text — the ONLY thing that travels as prompt; structured actions never do. */
    @Override
    public void submitPrompt(String text) {
        submitPrompt(text, "");
    }

    /**
     * Structured user action. Productive mode routes to the session's OWN state machine
     * ({@code ProductiveResearchSessionResources.dispatch} — which also republishes the MCP tool set);
     * FAKE mode routes through the fake backend exactly as before. Never a synthetic chat message,
     * never a silent no-op.
     */
    @Override
    public com.aresstack.askai.research.backend.ResearchCommandDispatchResult dispatch(
            ResearchCommandType command, String argument) {
        if (command == null) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.COMMAND_NOT_AVAILABLE, "No command given.");
        }
        if (disposed || (productiveResources != null && productiveResources.isClosed())) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.SESSION_CLOSED, "The research session is closed.");
        }
        if (!started || handle == null) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.SESSION_NOT_ACTIVE, "The research session is not active yet.");
        }
        if (productiveResources != null) {
            return dispatchProductive(command);
        }
        // FAKE mode: the deterministic backend owns its state machine; availability from the live memento.
        if (!canDispatch(command)) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.INVALID_PHASE,
                    "Not allowed in " + state.getPhaseId() + "/" + state.getStateId() + ".");
        }
        backend.executeCommand(handle, command);
        return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.accepted();
    }

    private com.aresstack.askai.research.backend.ResearchCommandDispatchResult dispatchProductive(
            ResearchCommandType command) {
        boolean allowed = stateFactory
                .restore(productiveResources.currentState())
                .getCurrentState().getAllowedCommands().contains(command);
        try {
            com.aresstack.askai.research.state.oo.ResearchStateTransitionResult result =
                    productiveResources.dispatch(command);
            if (!result.isAccepted()) {
                return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                        allowed ? com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                        .Status.DISPATCH_FAILED
                                : com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                        .Status.INVALID_PHASE,
                        result.getRejectionReason());
            }
            // PAUSE/CANCEL additionally stop the agent's running turn (transport concern, not state
            // logic) — off the EDT: writing to a busy agent's transport must never freeze the UI.
            if (command == ResearchCommandType.PAUSE || command == ResearchCommandType.CANCEL) {
                final ResearchSessionHandle cancelHandle = handle;
                Thread canceller = new Thread(new Runnable() {
                    public void run() {
                        try {
                            backend.cancel(cancelHandle);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }, "research-turn-cancel");
                canceller.setDaemon(true);
                canceller.start();
            }
            final com.aresstack.askai.research.state.oo.ResearchStateMemento next =
                    productiveResources.currentState();
            uiExecutor.execute(new Runnable() {
                public void run() {
                    state = next; // mirror the single truth into the view model, then notify observers
                    revision = next.getRevision();
                    invalidateNarration(); // in-flight warm texts belong to the previous situation
                    fireStateChanged();
                }
            });
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.accepted();
        } catch (RuntimeException ex) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.DISPATCH_FAILED, ex.getMessage() == null ? "dispatch failed"
                            : ex.getMessage());
        }
    }

    public void approveCurrent() {
        if (productiveResources != null) {
            // The machine knows WHICH approval fits the phase; the UI never re-encodes phase rules.
            ResearchCommandType approve = firstAllowedWithPrefix("APPROVE_");
            if (approve != null && dispatch(approve, null).isAccepted()) {
                // Continue AUTOMATICALLY with the stored research question — the user never has to
                // type it a second time. Auto-advance reaches RESEARCH/running, then the question
                // goes to the agent, which starts the autonomous web research.
                autoAdvanceTowardsResearch();
                maybeStartResearchTurn();
                // Present the NEXT decision gate's buttons (e.g. evidence -> draft): a gate reached by an
                // approval must never be a dead end. Working/terminal states show nothing.
                showRestoredActionsIfAny();
            }
            return;
        }
        String pendingApprovalId = state.getPendingApprovalId();
        if (handle != null && pendingApprovalId != null) {
            backend.approve(handle, pendingApprovalId);
        }
    }

    /** Sends the stored research question to the agent once the state actually reached RUNNING. */
    private void maybeStartResearchTurn() {
        if (productiveResources == null) {
            return;
        }
        if (!researchQuestion.isEmpty() && handle != null
                && com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING
                        .equals(productiveResources.currentState().getStateId())) {
            beginAgentTurn(); // busy + preempt visualizer; cleared by the turn's terminal event
            backend.submitPrompt(handle, new ResearchPrompt(researchQuestion, ""));
        }
    }

    public void requestChanges(String reason) {
        if (productiveResources != null) {
            ResearchCommandType request = firstAllowedWithPrefix("REQUEST_");
            if (request != null && dispatch(request, reason).isAccepted()) {
                // The resulting state may itself be a decision gate — present its buttons.
                showRestoredActionsIfAny();
            }
            return;
        }
        String pendingApprovalId = state.getPendingApprovalId();
        if (handle != null && pendingApprovalId != null) {
            backend.reject(handle, pendingApprovalId, reason);
        }
    }

    /**
     * The user's explicit, SOLE decision to leave SCOPING: approve the current research brief working copy
     * into an immutable revision and — only when that approval succeeds — dispatch exactly ONE
     * {@code SUBMIT_SCOPE}, so the state machine (the only transition authority) advances SCOPING → OUTLINE.
     * The order is fixed: persist/approve the artifact FIRST, transition only afterwards; a failed or empty
     * approval transitions nothing. This deliberately does NOT chain further phases (no
     * {@link #autoAdvanceTowardsResearch()}): one user click decides exactly one legal workflow transition.
     * No model output, advice, natural-language "weiter", search suggestion or visualizer result may reach
     * this — the scoping button is the only caller.
     * <p>
     * The result is a {@link ScopingApprovalOutcome}, never a bare boolean: a rejected click is NEVER a silent
     * no-op. The gate is re-checked HERE (the button's enabled state is only a snapshot from the last state
     * change — e.g. {@code agentTurnInFlight} can flip to true when a turn starts without a UI refresh), and
     * any non-{@link ScopingApprovalOutcome#SUCCESS} reason is both logged and surfaced to the user.
     */
    public ScopingApprovalOutcome approveScopingBriefAndContinue() {
        final com.aresstack.askai.research.state.oo.ResearchStateMemento snapshot =
                productiveResources != null ? productiveResources.currentState() : state;
        scopeApproveDiag("clicked session=" + Integer.toHexString(System.identityHashCode(this)));
        scopeApproveDiag("phase=" + snapshot.getPhaseId() + " state=" + snapshot.getStateId()
                + " busy=" + agentTurnInFlight + " briefPresent=" + hasNonBlankBrief());
        ScopingApprovalOutcome blocker = scopingApprovalBlocker();
        if (blocker != null) {
            scopeApproveDiag("blocked reason=" + blocker);
            surfaceScopingApprovalProblem(blocker);
            return blocker;
        }
        com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        // 1) Persist/approve the artifact FIRST. An identical, already-approved brief creates NO duplicate
        // revision (ALREADY_CURRENT); an I/O failure aborts here, BEFORE any state transition.
        com.aresstack.askai.research.store.ResearchBriefArtifact.Approval approval;
        try {
            approval = store.approveCurrent(System.currentTimeMillis());
        } catch (RuntimeException approvalFailed) {
            scopeApproveDiag("approveResult=FAILED " + approvalFailed.getClass().getSimpleName());
            surfaceScopingApprovalProblem(ScopingApprovalOutcome.APPROVAL_FAILED);
            return ScopingApprovalOutcome.APPROVAL_FAILED;
        }
        scopeApproveDiag("approveResult=" + approval.getStatus());
        scopeApproveDiag("before=" + snapshot.getPhaseId() + "/" + snapshot.getStateId());
        // 2) Only now the single, explicit transition — never autoAdvanceTowardsResearch(): exactly one step.
        boolean accepted = dispatch(ResearchCommandType.SUBMIT_SCOPE, null).isAccepted();
        scopeApproveDiag("dispatch accepted=" + accepted);
        if (!accepted) {
            surfaceScopingApprovalProblem(ScopingApprovalOutcome.TRANSITION_REJECTED);
            return ScopingApprovalOutcome.TRANSITION_REJECTED;
        }
        com.aresstack.askai.research.state.oo.ResearchStateMemento after =
                productiveResources.currentState();
        scopeApproveDiag("after=" + after.getPhaseId() + "/" + after.getStateId());
        // C5: SUBMIT_SCOPE now lands directly in RESEARCH (no outline gate). The user's ONE decision here IS
        // "finish scoping → research begins"; the technical WAITING→RUNNING step belongs to that same
        // decision, so the click also releases the research turn (with the stored question, when present).
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH.equals(after.getPhaseId())
                && com.aresstack.askai.research.state.oo.ResearchStateIds.WAITING.equals(after.getStateId())
                && dispatch(ResearchCommandType.START_RESEARCH, null).isAccepted()) {
            maybeStartResearchTurn();
        }
        return ScopingApprovalOutcome.SUCCESS;
    }

    /**
     * Whether the explicit "Fragestellung freigeben & weiter" action is currently legal — the button's enabled
     * state. It is exactly {@code scopingApprovalBlocker() == null}, so the enable check and the click check can
     * never drift apart.
     */
    public boolean canApproveScopingBriefAndContinue() {
        return scopingApprovalBlocker() == null;
    }

    /**
     * The concrete reason the scoping-approval action is currently unavailable, or an EMPTY string when it is
     * ready. The UI uses this for the disabled button's tooltip, so a greyed button is never an unexplained
     * dead end (the same plain-language text a rejected click would show).
     */
    public String scopingApprovalUnavailableReason() {
        ScopingApprovalOutcome blocker = scopingApprovalBlocker();
        return blocker == null ? "" : scopingApprovalProblemText(blocker);
    }

    // ------------------------------------------------------------------ user-triggered web search (service)

    /**
     * Run a USER-triggered web search — the third interaction kind, wired to the yellow scoping suggestions.
     * This is NOT an agent chat turn and NOT a workflow command: it never calls {@code submitText}, never
     * dispatches a state-machine command, never changes the phase and never starts an agent prompt. It is
     * phase-independent by contract; the yellow tags merely happen to exist only in SCOPING because that is
     * where the agent produces the suggestions, not because the service is gated to that phase.
     */
    public void requestManualWebSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        // The request SNAPSHOTS the session language: a live switch never changes a running search.
        com.aresstack.askai.research.search.ManualWebSearchHandle handle = manualWebSearchPort.search(
                new com.aresstack.askai.research.search.ManualWebSearchRequest(query,
                        sessionLanguage.currentLanguage()));
        // Remember the correlation id so inbound events of THIS search render and stale ones are ignored.
        this.activeManualSearchHandle = handle;
        this.activeManualSearchRequestId = handle == null ? null : handle.getRequestId();
        this.activeManualSearchQuery = query.trim();
        System.err.println("[manual-search] host submit requestId="
                + (handle == null ? "none" : handle.getRequestId()) + " queryLen=" + query.trim().length());
    }

    /** Cancel the in-flight user web search, if any; late events of the cancelled run are then ignored. */
    public void cancelManualWebSearch() {
        com.aresstack.askai.research.search.ManualWebSearchHandle handle = activeManualSearchHandle;
        if (handle != null) {
            handle.cancel();
        }
    }

    /** Whether a completed user web search already covered this exact query (normalized) — so it is not re-offered. */
    public boolean wasManuallySearched(String query) {
        return query != null
                && manualSearchedQueries.contains(query.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Rebuild the "already searched" set from the PERSISTED sources on restore: each source remembers the user
     * query that found it, so a covered query is never re-suggested even after an app restart. Best-effort.
     */
    private void restoreManualSearchedQueries() {
        com.aresstack.askai.research.sources.ResearchSourceRepository repository = getSourceRepository();
        if (repository == null) {
            return;
        }
        try {
            for (com.aresstack.askai.research.sources.ResearchSourceRecord record
                    : repository.find(com.aresstack.askai.research.sources.SourceQuery.all())) {
                String searched = record.getSearchQuery();
                if (searched != null && !searched.trim().isEmpty()) {
                    manualSearchedQueries.add(searched.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (RuntimeException bestEffort) {
            // a read failure just means no pre-population from disk
        }
    }

    /** Wire the productive manual-web-search service (tests / factory); a null port is ignored. */
    public void setManualWebSearchPort(com.aresstack.askai.research.search.ManualWebSearchPort port) {
        if (port != null) {
            this.manualWebSearchPort = port;
        }
    }

    /**
     * Render a user web search lifecycle event as a transient activity, correlated by requestId so late events
     * of a superseded or cancelled run are ignored. It changes NO phase and NO state — a pure service surface.
     */
    private void applyManualSearch(ResearchBackendEvent event) {
        if (sink == null) {
            return;
        }
        String requestId = event.getTechnicalDetail();
        if (requestId == null || !requestId.equals(activeManualSearchRequestId)) {
            // Visible in the diagnostics when the review chain breaks: which event was dropped and why.
            System.err.println("[manual-search] host event " + event.getTitle()
                    + " DROPPED (stale) requestId=" + requestId
                    + " active=" + activeManualSearchRequestId);
            return; // stale/late event from a request the user did not (or no longer) launched
        }
        String activityId = event.getActivityId() != null
                ? event.getActivityId() : "manual-search-" + requestId;
        String subKind = event.getTitle();
        String message = event.getText();
        System.err.println("[manual-search] host event " + subKind + " requestId=" + requestId);
        if ("started".equals(subKind)) {
            // ONE unified, persisted breadcrumb for BOTH entry points (typed /search AND a yellow-suggestion
            // click both funnel through here): a muted italic "Websuche: <query>" line that survives a restart.
            // The transient amber progress card runs alongside it and is ephemeral.
            sink.appendInfoMessage("manual-search-line-" + requestId, message);
            sink.startToolActivity(activityId, "Websuche", message);
        } else if ("progress".equals(subKind)) {
            sink.updateToolActivity(activityId, "Websuche", message);
        } else if ("completed".equals(subKind)) {
            sink.completeToolActivity(activityId, message);
            // This query is now covered: remember it so the agent's suggestions never re-offer it, and the
            // clicked yellow tag disappears (the accessory filters searched queries) and the list re-arranges.
            String searched = activeManualSearchQuery;
            if (searched != null && !searched.trim().isEmpty()) {
                manualSearchedQueries.add(searched.trim().toLowerCase(java.util.Locale.ROOT));
            }
            // Keep activeManualSearchRequestId set so the following review_* events still correlate; it is
            // cleared on review_finished / failed. The browser closes now; the bot's post-search review (if
            // any) is bracketed by review_started/review_finished below.
            stopManualSearchBrowser();
        } else if ("review_started".equals(subKind)) {
            // The bot is now at the wheel (skimming the new sources, refreshing suggestions): show a thinking
            // bubble AND make the composer BUSY (red, cancellable) exactly like a normal agent turn, so the
            // user both sees the work and can abort it. Cleared by review_finished (always emitted).
            postSearchThinkingId = "post-search-summary-" + requestId;
            postSearchSummaryInFlight = true;
            agentTurnInFlight = true;
            // Phase-neutral wording: outside scoping there are no suggestions to refresh, but the
            // summary review runs everywhere.
            sink.startThinking(postSearchThinkingId, "Ich sichte die neuen Quellen …");
        } else if ("review_finished".equals(subKind)) {
            finishPostSearchThinking("");
            agentTurnInFlight = false; // release the composer — the review is over (success, failure or cancel)
            activeManualSearchRequestId = null;
        } else if ("failed".equals(subKind)) {
            // Both surfaces: close the transient activity AND raise a PERSISTENT, readable problem so the
            // reason does not merely flash away.
            sink.failToolActivity(activityId, message);
            sink.showProblem("manual-search-failed-" + requestId, message);
            activeManualSearchRequestId = null;
            finishPostSearchThinking(""); // no summary is coming
            agentTurnInFlight = false;
            stopManualSearchBrowser();
        }
    }

    /** End the post-search thinking bubble + release the composer (red send button), if one is in flight. */
    private void finishPostSearchThinking(String summary) {
        if (!postSearchSummaryInFlight) {
            return;
        }
        postSearchSummaryInFlight = false;
        if (postSearchThinkingId != null && sink != null) {
            sink.finishThinking(postSearchThinkingId, summary == null ? "" : summary);
        }
        postSearchThinkingId = null;
    }

    /**
     * A user web search has TERMINATED (completed/failed): stop the host-owned Playwright browser sidecar so
     * it never lingers open — the same lifecycle the autonomous run gets on RUN_OUTCOME. It is only called on
     * terminal events (a CAPTCHA/challenge wait emits {@code progress}, never {@code completed}/{@code failed}),
     * so an in-flight manual challenge is never cut off.
     */
    private void stopManualSearchBrowser() {
        if (productiveResources != null) {
            productiveResources.stopBrowserPhase();
        }
    }

    /**
     * The single source of truth for the scoping-approval gate: returns the concrete blocking reason, or
     * {@code null} when the action is legal. Pure gate — a productive session that is still open, NO foreground
     * agent turn in flight, the active phase is SCOPING with {@code SUBMIT_SCOPE} allowed by the state machine,
     * and a non-blank research brief. No model quality/gatekeeper check.
     */
    private ScopingApprovalOutcome scopingApprovalBlocker() {
        if (disposed || productiveResources == null || productiveResources.isClosed() || handle == null) {
            return ScopingApprovalOutcome.SESSION_INACTIVE;
        }
        if (agentTurnInFlight) {
            return ScopingApprovalOutcome.BUSY; // a foreground agent turn is in flight
        }
        com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                productiveResources.currentState();
        if (!com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(memento.getPhaseId())
                || !currentAllowedCommands().contains(ResearchCommandType.SUBMIT_SCOPE)) {
            return ScopingApprovalOutcome.WRONG_PHASE;
        }
        if (!hasNonBlankBrief()) {
            return ScopingApprovalOutcome.MISSING_BRIEF;
        }
        return null; // ready
    }

    private boolean hasNonBlankBrief() {
        com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        return store != null && !store.effectiveContent().trim().isEmpty();
    }

    /** Make a rejected scoping approval VISIBLE (never a silent no-op), with a concrete plain-language reason. */
    private void surfaceScopingApprovalProblem(final ScopingApprovalOutcome outcome) {
        if (sink == null) {
            return;
        }
        final String message = scopingApprovalProblemText(outcome);
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.showProblem("scope-approve-" + outcome, message);
            }
        });
    }

    private static String scopingApprovalProblemText(ScopingApprovalOutcome outcome) {
        String reason;
        switch (outcome) {
            case BUSY:
                reason = "Es läuft gerade eine Agent-Antwort. Bitte einen Moment warten.";
                break;
            case MISSING_BRIEF:
                reason = "Es liegt noch keine Fragestellung vor.";
                break;
            case WRONG_PHASE:
                reason = "In dieser Phase ist der Wechsel nicht möglich.";
                break;
            case APPROVAL_FAILED:
                reason = "Der Brief konnte nicht gespeichert werden.";
                break;
            case TRANSITION_REJECTED:
                reason = "Phasenwechsel wurde abgelehnt.";
                break;
            case SESSION_INACTIVE:
                reason = "Die Sitzung ist nicht aktiv.";
                break;
            default:
                reason = "Unbekannter Grund.";
                break;
        }
        return "Fragestellung konnte nicht freigegeben werden: " + reason;
    }

    /** Compact, non-sensitive trace of the scoping-approval click path (runWithDevPlugins console). */
    private static void scopeApproveDiag(String message) {
        System.err.println("[scope-approve] " + message);
    }

    public void pause() {
        if (productiveResources != null) {
            agentTurnInFlight = false;
            if (dispatch(ResearchCommandType.PAUSE, null).isAccepted()) {
                // A visible confirmation — and the sink event makes the composer re-read availability.
                narrateAsAgent("paused", narrator.pausedNotice());
            }
            return;
        } else if (handle != null) {
            backend.pause(handle);
        }
    }

    public void resume() {
        if (productiveResources != null) {
            dispatch(ResearchCommandType.RESUME, null);
        } else if (handle != null) {
            backend.resume(handle);
        }
    }

    public void cancel() {
        if (postSearchSummaryInFlight) {
            // The bot's post-search review is the turn in flight. It runs inside the manual-search operation,
            // so cancelling THAT aborts the review's model call (via the runtime @Cancel → cancelInFlight).
            // Release the composer immediately; the runtime still emits review_finished to settle tracking.
            cancelManualWebSearch();
            finishPostSearchThinking("");
            agentTurnInFlight = false;
            fireStateChanged();
            return;
        }
        if (productiveResources != null) {
            agentTurnInFlight = false;
            dispatch(ResearchCommandType.CANCEL, null);
        } else if (handle != null) {
            backend.cancel(handle);
        }
    }

    public boolean canDispatch(ResearchCommandType type) {
        return handle != null && currentAllowedCommands().contains(type);
    }

    /** The live allowed set — productive mode reads the authoritative resources state directly. */
    public java.util.Set<ResearchCommandType> currentAllowedCommands() {
        com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                productiveResources != null ? productiveResources.currentState() : state;
        return stateFactory.restore(memento).getCurrentState().getAllowedCommands();
    }

    private ResearchCommandType firstAllowedWithPrefix(String prefix) {
        for (ResearchCommandType type : currentAllowedCommands()) {
            if (type.name().startsWith(prefix)) {
                return type;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ event intake (backend thread → UI)

    @Override
    public void onEvent(final ResearchBackendEvent event) {
        if (disposed || !started || !request.getSessionId().equals(event.getSessionId())) {
            return;
        }
        uiExecutor.execute(new Runnable() {
            public void run() {
                applyEvent(event);
            }
        });
    }

    private void applyEvent(ResearchBackendEvent event) {
        if (disposed || !started || !request.getSessionId().equals(event.getSessionId())) {
            return;
        }
        if (event.getSequenceNumber() <= lastSequence) {
            return; // stale or duplicate delivery
        }
        lastSequence = event.getSequenceNumber();
        revision = event.getRevision();
        switch (event.getType()) {
            case SESSION_STATE_CHANGED:
                if (event.getStateMemento() != null) {
                    state = event.getStateMemento(); // the exact live truth: phase/state/continuation/approvalId
                }
                String stateId = state.getStateId();
                if (!com.aresstack.askai.research.state.oo.ResearchStateIds.BLOCKED.equals(stateId)
                        && !com.aresstack.askai.research.state.oo.ResearchStateIds.FAILED.equals(stateId)) {
                    problemMessage = "";
                }
                break;
            case APPROVAL_REQUESTED:
                // The pending approval id already lives in the memento; this only drives the chat approval bubble.
                sink.requestApproval(event.getApprovalId(), event.getText());
                break;
            case ACTIVITY:
                applyActivity(event);
                break;
            case USER_MESSAGE:
                sink.appendUserMessage(event.getEventId(), event.getText());
                break;
            case COMPLETED:
                // The technical turn terminal is INVISIBLE (point 9): it only frees the composer and
                // closes a still-open progress card. The user-facing message is the RUN_OUTCOME card.
                agentTurnInFlight = false;
                // Always route through the sink so the composer re-reads its availability, even when no
                // progress card exists (the sink refresh runs also for unknown activity ids).
                sink.completeToolActivity(currentRunActivityId != null
                        ? currentRunActivityId : "research-turn", "");
                currentRunActivityId = null;
                break;
            case ASSISTANT_MESSAGE:
                // If the bot's post-search summary is what is arriving, collapse the thinking bubble first so
                // the summary renders as the assistant turn and the composer is released.
                if (postSearchSummaryInFlight) {
                    System.err.println("[manual-search] host assistant summary received requestId="
                            + activeManualSearchRequestId);
                }
                finishPostSearchThinking("");
                sink.appendAssistantMessage(event.getEventId(), event.getText());
                break;
            case RUN_LOG:
                applyRunLog(event);
                break;
            case RUN_PROGRESS:
                applyRunProgress(event);
                break;
            case RUN_OUTCOME:
                applyRunOutcome(event);
                // The research/browsing run ended: stop the browser phase (async, off-EDT) but keep the
                // TeamAgent alive; a later research run lazily starts a fresh browser generation.
                if (productiveResources != null) {
                    productiveResources.stopBrowserPhase();
                }
                break;
            case USER_ATTENTION:
                applyUserAttention(event);
                break;
            case SCOPE_PROPOSAL:
                handleScopeProposal(event.getText(), event.getTechnicalDetail());
                break;
            case GREETING_DONE:
                handleGreetingDone();
                break;
            case SCOPING_PROJECTION:
                // Display-only support content for the scoping workspace: keep only the LATEST projection
                // (a later turn replaces it — the chat keeps every turn, this panel shows the current state).
                // It moves nothing and writes no artifact; fireStateChanged() lets the workspace re-read it.
                latestScopingProjection = event.getScopingProjection();
                persistScopingProjection(latestScopingProjection); // survive a restart (display-only working state)
                finishPostSearchThinking(""); // refreshed suggestions are the last step of the summary
                break;
            case RESEARCH_BRIEF:
                // The phase artifact: persist the brief to its working copy (one path, off the EDT). No
                // approval revision, no phase transition — the "Fragestellung" view re-reads the store.
                persistResearchBrief(event.getTitle(), event.getText());
                break;
            case MANUAL_SEARCH:
                // A USER-triggered web search lifecycle: a transient activity only — no phase, no state change.
                applyManualSearch(event);
                break;
            case BLOCKED:
            case ERROR:
                agentTurnInFlight = false; // a failed turn must not wedge the composer
                finishPostSearchThinking(""); // never leave the post-search bubble/red send button stuck
                problemMessage = event.getPublicMessage();
                // Show the WHY, not just the what: the technical detail (exception phase + reason,
                // never secrets) is the only way anyone can act on a start failure.
                String detail = event.getTechnicalDetail();
                sink.showProblem(event.getEventId(), detail == null || detail.isEmpty()
                        ? event.getPublicMessage()
                        : event.getPublicMessage() + "\n" + detail);
                break;
            default:
                break; // SOURCE_ADDED/FINDING_ADDED/OUTLINE_CHANGED/PROBLEM_REPORTED handled by artifact views
        }
        fireStateChanged(); // the State visualization re-reads the domain snapshot
    }

    // ------------------------------------------------------------------ state visualization support

    /** The latest scoping assistant projection (search suggestions), or {@code null} if none yet. */
    public com.aresstack.askai.research.backend.ScopingAssistantUpdate latestScopingProjection() {
        return latestScopingProjection;
    }

    /** File-backed store of the latest scoping projection so the yellow tags survive a restart. Lazy; null in the clickdummy. */
    private com.aresstack.askai.research.store.FileScopingProjectionStore scopingProjectionStore;

    /** The per-project store for the latest scoping projection (yellow suggestion tags); {@code null} in the clickdummy. */
    private synchronized com.aresstack.askai.research.store.FileScopingProjectionStore scopingProjectionStore() {
        if (scopingProjectionStore == null && productiveResources != null && !productiveResources.isClosed()) {
            java.io.File projectDir = productiveResources.getProjectContext().getProjectDirectory();
            scopingProjectionStore = new com.aresstack.askai.research.store.FileScopingProjectionStore(
                    new java.io.File(projectDir, "scoping"));
        }
        return scopingProjectionStore;
    }

    /**
     * Restore the persisted scoping projection (yellow tags) after a restart — the transcript comes back from
     * history but this display-only state was previously in-memory only. Only fills a still-empty projection so
     * a live one is never clobbered; already-searched suggestions are filtered by {@link #wasManuallySearched}.
     */
    private void persistScopingProjection(
            com.aresstack.askai.research.backend.ScopingAssistantUpdate projection) {
        com.aresstack.askai.research.store.FileScopingProjectionStore store = scopingProjectionStore();
        if (store != null && projection != null) {
            store.save(projection);
        }
    }

    private void restoreScopingProjection() {
        if (latestScopingProjection != null) {
            return;
        }
        com.aresstack.askai.research.store.FileScopingProjectionStore store = scopingProjectionStore();
        if (store == null) {
            return;
        }
        com.aresstack.askai.research.backend.ScopingAssistantUpdate restored = store.load();
        if (restored != null) {
            latestScopingProjection = restored;
            fireStateChanged(); // let a mounted scoping accessory re-read + render the tags
        }
    }

    /**
     * The file-backed research brief store bound to this session's project directory — the SINGLE source of
     * truth for the brief. {@code null} in the in-memory clickdummy. The "Fragestellung" view reads it.
     */
    public synchronized com.aresstack.askai.research.store.FileResearchBriefStore researchBriefStore() {
        if (researchBriefStore == null && productiveResources != null && !productiveResources.isClosed()) {
            java.io.File projectDir = productiveResources.getProjectContext().getProjectDirectory();
            researchBriefStore = new com.aresstack.askai.research.store.FileResearchBriefStore(
                    new java.io.File(projectDir, "brief"));
        }
        return researchBriefStore;
    }

    /**
     * Persist the research brief to its working copy, OFF the EDT, and refresh the view only when it actually
     * changed (no duplicate write/revision for an identical brief). This is the ONLY brief persistence path;
     * the brief is never mirrored into a session field as an alternative source of truth. No approval, no
     * transition.
     */
    private void persistResearchBrief(final String phaseId, final String markdown) {
        final com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        if (store == null || markdown == null || markdown.trim().isEmpty()) {
            return;
        }
        briefWriteExecutor.execute(new Runnable() {
            public void run() {
                boolean changed;
                try {
                    changed = store.updateWorkingCopy(markdown, System.currentTimeMillis());
                } catch (RuntimeException persistFailed) {
                    return; // a brief write failure must never crash the session or the run
                }
                com.aresstack.askai.research.visualize.VisualizerDiagnostics.log(
                        "briefChanged changed=" + changed + " briefChars=" + markdown.length());
                if (changed) {
                    // Refresh the Fragestellung view, and lazily (re)build the DERIVED visualization for the
                    // new brief — a separate consumer, hash-guarded, that never blocks this write or the agent.
                    scheduleVisualization(phaseId, markdown);
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            fireStateChanged();
                        }
                    });
                }
            }
        });
    }

    /** The latest derived visualization of an artifact, or {@code null} until one has been produced. */
    public com.aresstack.askai.research.visualize.VisualizationProjection latestVisualization() {
        return latestVisualization;
    }

    /** The visualization lifecycle status (never-ran / preparing / running / diagram / none / failed). */
    public com.aresstack.askai.research.visualize.VisualizationStatus visualizationStatus() {
        return visualizationStatus;
    }

    private void scheduleVisualization(String phaseId, String markdown) {
        com.aresstack.askai.research.visualize.LazyArtifactVisualizer visualizer = artifactVisualizer();
        if (visualizer == null) {
            com.aresstack.askai.research.visualize.VisualizerDiagnostics.log(
                    "unavailable reason=no AgentInferencePort");
            visualizationStatus = com.aresstack.askai.research.visualize.VisualizationStatus.FAILED;
            uiExecutor.execute(new Runnable() {
                public void run() {
                    fireStateChanged();
                }
            });
            return;
        }
        visualizer.onArtifactChanged(new com.aresstack.askai.research.visualize.ArtifactSnapshot(
                "research-brief", markdown, phaseId));
    }

    private synchronized com.aresstack.askai.research.visualize.LazyArtifactVisualizer artifactVisualizer() {
        if (artifactVisualizer == null && !disposed) {
            com.aresstack.askai.agent.model.inference.AgentInferencePort port = hostContext == null ? null
                    : hostContext.getService(com.aresstack.askai.agent.model.inference.AgentInferencePort.class);
            if (port == null) {
                return null; // no host inference (e.g. clickdummy): no visualization, brief still works
            }
            artifactVisualizer = new com.aresstack.askai.research.visualize.LazyArtifactVisualizer(
                    new com.aresstack.askai.research.visualize.ModelArtifactVisualizer(port),
                    new java.util.function.BooleanSupplier() {
                        public boolean getAsBoolean() {
                            return agentTurnInFlight; // defer while the main agent works
                        }
                    },
                    new java.util.function.Consumer<
                            com.aresstack.askai.research.visualize.VisualizationProjection>() {
                        public void accept(
                                com.aresstack.askai.research.visualize.VisualizationProjection projection) {
                            latestVisualization = projection;
                            uiExecutor.execute(new Runnable() {
                                public void run() {
                                    fireStateChanged();
                                }
                            });
                        }
                    },
                    new java.util.function.Consumer<
                            com.aresstack.askai.research.visualize.VisualizationStatus>() {
                        public void accept(
                                com.aresstack.askai.research.visualize.VisualizationStatus status) {
                            visualizationStatus = status;
                            uiExecutor.execute(new Runnable() {
                                public void run() {
                                    fireStateChanged();
                                }
                            });
                        }
                    });
        }
        return artifactVisualizer;
    }

    public void addStateListener(Runnable listener) {
        if (listener != null) {
            stateListeners.addIfAbsent(listener);
        }
    }

    public void removeStateListener(Runnable listener) {
        stateListeners.remove(listener);
    }

    private void fireStateChanged() {
        for (Runnable listener : stateListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                // A broken observer must never take the session (or other observers) down.
            }
        }
    }

    /**
     * A read-only snapshot of the hierarchical state, rebuilt from the live {@link ResearchStateMemento} — the
     * exact phase/state/continuation/approval id, never a defaulted continuation.
     */
    public ResearchStateSnapshot currentResearchSnapshot() {
        return ResearchStateSnapshot.of(stateFactory.restore(state), revision, problemMessage);
    }

    // ------------------------------------------------------------------ run progress / outcome cards

    /** The one in-place progress card of the active run (null when no run is being rendered). */
    private String currentRunActivityId;
    private boolean runCardStarted;
    private com.aresstack.askai.research.backend.ResearchRunProgressInfo lastRunProgress;
    /** The visible activity context of the active run: what is searched, which page is open right now. */
    private String runSearchQuery = "";
    private String runCurrentHost = "";
    private String runCurrentPageTitle = "";
    /** Non-empty while the run searches over a REST provider — the search shows NO browser. */
    private String runApiSearchProvider = "";
    /** The open thought bubble of the REST search, or null (closed when the results are in). */
    private String apiSearchBubbleId;
    /** Bounded, user-readable history of the last processed websites (accepted/skipped) in the card. */
    private final java.util.ArrayDeque<String> runActivityHistory = new java.util.ArrayDeque<String>();
    private static final int RUN_HISTORY_LINES = 5;

    private void applyRunLog(ResearchBackendEvent event) {
        // Full diagnostics belong EXCLUSIVELY to the host's collapsed "Technical details" area — the
        // visible progress card never carries raw log lines, source ids or redirect URLs.
        sink.appendTechnicalLog(event.getText());
    }

    private void applyRunProgress(ResearchBackendEvent event) {
        com.aresstack.askai.research.backend.ResearchRunProgressInfo info = event.getRunProgress();
        String id = event.getActivityId();
        boolean newCard = !runCardStarted || !id.equals(currentRunActivityId);
        if (newCard) {
            resetRunActivityContext();
        }
        lastRunProgress = info;
        rememberRunActivity(info);
        updateApiSearchBubble(info, id);
        if (newCard) {
            currentRunActivityId = id;
            runCardStarted = true;
            sink.startToolActivity(id, playbook.progressTitle(), progressCardBody());
        } else {
            sink.updateToolActivity(id, playbook.progressTitle(), progressCardBody());
        }
    }

    /**
     * The REST search is a THOUGHT BUBBLE, not browser activity: it opens with the first SEARCHING_API
     * progress and closes as soon as the run moves on (results in → the browser then only opens the
     * individual result pages, exactly as before).
     */
    private void updateApiSearchBubble(
            com.aresstack.askai.research.backend.ResearchRunProgressInfo info, String runId) {
        boolean searchingViaApi = "SEARCHING_API".equals(info.getActivityToken());
        if (searchingViaApi && apiSearchBubbleId == null) {
            apiSearchBubbleId = "api-search-" + runId;
            sink.startThinking(apiSearchBubbleId, playbook.apiSearchThinking(
                    runApiSearchProvider, runSearchQuery));
        } else if (!searchingViaApi && apiSearchBubbleId != null) {
            sink.finishThinking(apiSearchBubbleId,
                    playbook.apiSearchDone(runApiSearchProvider));
            apiSearchBubbleId = null;
        }
    }

    private void resetRunActivityContext() {
        if (apiSearchBubbleId != null && sink != null) {
            // A run that ends while the bubble is open (cancel, provider error) must not leave it thinking.
            sink.finishThinking(apiSearchBubbleId, playbook.apiSearchDone(runApiSearchProvider));
            apiSearchBubbleId = null;
        }
        runSearchQuery = "";
        runCurrentHost = "";
        runCurrentPageTitle = "";
        runApiSearchProvider = "";
        runActivityHistory.clear();
    }

    /** Fold one progress snapshot into the card's visible activity context + bounded history. */
    private void rememberRunActivity(com.aresstack.askai.research.backend.ResearchRunProgressInfo info) {
        if (!info.getSearchQuery().isEmpty()) {
            runSearchQuery = info.getSearchQuery();
        }
        if ("SEARCHING_API".equals(info.getActivityToken())) {
            // The provider LABEL travels in the host field — it is not a website and never becomes
            // "currently open"; the search step involves no browser at all.
            runApiSearchProvider = info.getCurrentHost();
            return;
        }
        if (!info.getCurrentHost().isEmpty()) {
            runCurrentHost = info.getCurrentHost();
            runCurrentPageTitle = info.getCurrentPageTitle();
        }
        String token = info.getActivityToken();
        if ("SOURCE_ACCEPTED".equals(token) && !info.getCurrentHost().isEmpty()) {
            pushRunHistory(playbook.historyAccepted(info.getCurrentHost(),
                    info.getCurrentPageTitle()));
        } else if ("PAGE_SKIPPED".equals(token) && !info.getCurrentHost().isEmpty()) {
            pushRunHistory(playbook.historySkipped(info.getCurrentHost()));
        }
    }

    private void pushRunHistory(String entry) {
        runActivityHistory.addLast(entry);
        while (runActivityHistory.size() > RUN_HISTORY_LINES) {
            runActivityHistory.removeFirst();
        }
    }

    private void applyRunOutcome(ResearchBackendEvent event) {
        agentTurnInFlight = false; // the run is over; the user decides the next step
        final com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome = event.getRunOutcome();
        if (runCardStarted && currentRunActivityId != null) {
            sink.completeToolActivity(currentRunActivityId, playbook.runFinishedSummary(
                    outcome.getPagesVisited(), outcome.getAcceptedSources(), outcome.getDistinctHosts()));
        }
        currentRunActivityId = null;
        runCardStarted = false;
        resetRunActivityContext();
        sink.showActionCard("research-outcome-" + outcome.getPromptId(),
                narrator.outcomeNarrative(outcome), outcomeActions(outcome),
                new AgentConversationSink.ActionHandler() {
                    public AgentConversationSink.ActionExecutionResult onAction(String actionId) {
                        return handleOutcomeAction(actionId, outcome);
                    }
                });
    }

    // ------------------------------------------------------------------ user attention (manual challenge)

    /** Domain families with a visible attention notice; guards the once-per-episode sound. */
    private final java.util.Set<String> attentionEpisodes = new java.util.HashSet<String>();
    /** Injectable for tests; default: one audible attention beep. */
    private volatile Runnable attentionSound = new Runnable() {
        public void run() {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
            } catch (RuntimeException ignored) {
                // headless/CI: no sound device is never an error
            }
        }
    };

    /** Test seam: replace the attention sound. */
    public void setAttentionSound(Runnable sound) {
        this.attentionSound = sound == null ? new Runnable() {
            public void run() {
            }
        } : sound;
    }

    /** REQUIRED → persistent visible notice + ONE sound per episode; RESOLVED → visible all-clear. */
    private void applyUserAttention(ResearchBackendEvent event) {
        String domain = event.getPublicMessage() == null ? "" : event.getPublicMessage();
        boolean resolved = "RESOLVED".equals(event.getText());
        if (!resolved) {
            if (attentionEpisodes.add(domain)) {
                attentionSound.run();
                sink.showProblem("attention-" + domain, playbook.attentionRequired(domain));
            }
            return;
        }
        if (attentionEpisodes.remove(domain)) {
            sink.appendAssistantMessage("attention-resolved-" + domain,
                    playbook.attentionResolved(domain));
        }
    }

    /**
     * The card's visible body: what is searched, which real website is open right now (final host +
     * page title), the counters and a bounded history of the last processed websites. No raw URLs,
     * no source ids, no log lines — those live in the host's collapsed "Technical details" only.
     */
    private String progressCardBody() {
        StringBuilder sb = new StringBuilder();
        if (lastRunProgress != null) {
            if (!runSearchQuery.isEmpty()) {
                sb.append(runApiSearchProvider.isEmpty()
                        ? playbook.progressSearchLine(runSearchQuery)
                        : playbook.progressApiSearchLine(runApiSearchProvider, runSearchQuery))
                        .append("\n\n");
            }
            if (!runCurrentHost.isEmpty()) {
                sb.append(playbook.progressPageLine(runCurrentHost, runCurrentPageTitle))
                        .append("\n\n");
            }
            sb.append(playbook.progressLine(lastRunProgress.getPagesVisited(),
                    lastRunProgress.getAcceptedSources(), lastRunProgress.getDistinctHosts(),
                    lastRunProgress.getActivityToken()));
            if (!runActivityHistory.isEmpty()) {
                sb.append("\n\n").append(playbook.recentPagesTitle());
                for (String entry : runActivityHistory) {
                    sb.append('\n').append(entry);
                }
            }
        }
        return sb.toString();
    }

    /** The typed actions offered on the result card — chosen by stop situation (never enum names). */
    private java.util.List<AgentConversationSink.ActionOption> outcomeActions(
            com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        java.util.List<String> ids = new ArrayList<String>();
        String stop = o.getStopReason();
        if ("USER_CANCELLED".equals(stop)) {
            ids.add("resume");
            ids.add("sources");
            ids.add("end");
        } else if ("MCP_UNAVAILABLE".equals(stop)) {
            ids.add("retry");
            ids.add("config");
        } else if ("ERROR_BUDGET_EXHAUSTED".equals(stop)) {
            ids.add("retry");
            ids.add("sources");
            ids.add("end");
        } else if ("SUFFICIENT_EVIDENCE".equals(stop)
                || ("SOURCE_BUDGET_EXHAUSTED".equals(stop) && o.isEvidenceSufficient())) {
            ids.add("review");
            ids.add("sources");
            ids.add("end");
        } else if ("RERANKER_UNAVAILABLE".equals(stop) || "RERANKER_TIMEOUT".equals(stop)
                || "RERANKER_INVALID_RESPONSE".equals(stop) || "SEARCH_TECHNICAL_PROBLEM".equals(stop)) {
            // Technical search/reranker failures: retry or fix the configuration — NEVER "accept the
            // limitation" (there is no research result to accept, only a failed component).
            ids.add("retry");
            ids.add("config");
            ids.add("end");
        } else if ("RERANKER_CONFIGURATION_ERROR".equals(stop)) {
            // The snapshot/selection is invalid: fixing the configuration comes FIRST.
            ids.add("config");
            ids.add("retry");
            ids.add("end");
        } else if ("NO_SEMANTIC_MATCHES".equals(stop)) {
            // Semantic outcome, not a failure: no candidate passed the selection policy.
            ids.add("refine");
            ids.add("sources");
            ids.add("end");
        } else if ("NO_RELEVANT_PATHS".equals(stop) && !o.isEvidenceSufficient()) {
            ids.add("refine");
            ids.add("sources");
            ids.add("end");
        } else {
            // Budget exhausted with open evidence requirements — the screenshotted case.
            ids.add("continue");
            ids.add("sources");
            ids.add("refine");
            ids.add("limit");
            ids.add("end");
        }
        java.util.List<AgentConversationSink.ActionOption> options =
                new ArrayList<AgentConversationSink.ActionOption>();
        for (String id : ids) {
            // Viewing something (sources tab, configuration) never consumes the decision card.
            AgentConversationSink.ActionKind kind = "sources".equals(id) || "config".equals(id)
                    ? AgentConversationSink.ActionKind.NAVIGATION
                    : AgentConversationSink.ActionKind.DECISION;
            options.add(new AgentConversationSink.ActionOption(id, playbook.actionLabel(id), kind));
        }
        return options;
    }

    /** Typed result-card actions — dispatched over the command port, never synthetic chat messages. */
    private AgentConversationSink.ActionExecutionResult handleOutcomeAction(String actionId,
            com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome) {
        if ("continue".equals(actionId) || "retry".equals(actionId) || "resume".equals(actionId)) {
            continueResearchTurn();
            return AgentConversationSink.ActionExecutionResult.ACCEPTED;
        }
        if ("sources".equals(actionId)) {
            // Pure NAVIGATION: showing a tab never consumes the result card.
            openArtifactView("sources");
            return AgentConversationSink.ActionExecutionResult.NO_STATE_CHANGE;
        }
        if ("config".equals(actionId)) {
            // The runtime configuration lives in the gear menu at the composer now (session-based
            // plugin settings), not in the artifact area — point there instead of opening a tab.
            sayAsAgent(playbook.isGerman()
                    ? "Die Einstellungen findest du im Zahnrad-Menü unten am Eingabefeld "
                            + "(Kategorie „Research Agent“)."
                    : "You find the settings in the gear menu at the composer "
                            + "(category „Research Agent“).");
            return AgentConversationSink.ActionExecutionResult.NO_STATE_CHANGE;
        }
        if ("refine".equals(actionId)) {
            narrateAsAgent("refine", narrator.refinePrompt()); // the composer is free; the user just types
            return AgentConversationSink.ActionExecutionResult.ACCEPTED;
        }
        if ("limit".equals(actionId)) {
            recordLimitation(outcome);
            return AgentConversationSink.ActionExecutionResult.ACCEPTED;
        }
        if ("end".equals(actionId)) {
            cancel(); // the controlled end of the research phase (state machine stays the authority)
            return AgentConversationSink.ActionExecutionResult.ACCEPTED;
        }
        if ("review".equals(actionId)) {
            requestEvidenceReview();
            return AgentConversationSink.ActionExecutionResult.ACCEPTED;
        }
        return AgentConversationSink.ActionExecutionResult.NO_STATE_CHANGE;
    }

    /** Continue with the STORED question, a fresh budget and no re-visits (the agent keeps its history). */
    private void continueResearchTurn() {
        if (productiveResources == null || handle == null) {
            return;
        }
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.PAUSED
                .equals(productiveResources.currentState().getStateId())) {
            dispatch(ResearchCommandType.RESUME, null);
        }
        if (!researchQuestion.isEmpty()
                && com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING
                        .equals(productiveResources.currentState().getStateId())) {
            agentTurnInFlight = true; // cleared by the next RUN_OUTCOME / terminal
            backend.submitPrompt(handle, new ResearchPrompt(researchQuestion, ""));
        } else {
            narrateAsAgent("refine", narrator.refinePrompt());
        }
    }

    /** Reveal an artifact tab via the host service; degrade VISIBLY when the host offers none. */
    private void openArtifactView(String artifactId) {
        com.aresstack.askai.plugin.api.service.ArtifactViewOpener opener = hostContext == null
                ? null : hostContext.getService(com.aresstack.askai.plugin.api.service.ArtifactViewOpener.class);
        if (opener != null) {
            opener.openArtifact(artifactId);
        } else {
            sayAsAgent(playbook.isGerman()
                    ? "Die Ansicht kann hier nicht geöffnet werden — bitte öffne den Tab \"" + artifactId
                            + "\" im Arbeitsbereich."
                    : "This view cannot be opened here — please open the \"" + artifactId
                            + "\" tab in the workspace.");
        }
    }

    /** Record the unmet evidence requirement VISIBLY and move on towards review — never silently. */
    private void recordLimitation(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome) {
        String note = playbook.limitationRecorded(outcome);
        try {
            com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore store =
                    productiveResources != null ? productiveResources.getArtifactStore() : artifactStore;
            com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent notes =
                    store.read("research-notes");
            store.replace("research-notes", notes.getRevision(),
                    (notes.getMarkdown().isEmpty() ? "" : notes.getMarkdown() + "\n\n") + "> " + note);
        } catch (RuntimeException ignored) {
            // The visible chat confirmation below is the primary record; a store hiccup must not block it.
        }
        sayAsAgent(note);
        requestEvidenceReview();
    }

    /** Move on to the evidence review when the state machine allows it (the machine stays authority). */
    private void requestEvidenceReview() {
        if (currentAllowedCommands().contains(ResearchCommandType.REQUEST_EVIDENCE_REVIEW)
                && dispatch(ResearchCommandType.REQUEST_EVIDENCE_REVIEW, null).isAccepted()) {
            // The evidence gate (EVIDENCE/waiting_approval) now presents its approve/request-changes buttons —
            // without this the review click advanced the state but showed nothing ("geht nicht weiter").
            showRestoredActionsIfAny();
        }
    }

    private void applyActivity(ResearchBackendEvent event) {
        String id = event.getActivityId();
        switch (event.getActivityKind()) {
            case THINKING_STARTED:
                sink.startThinking(id, event.getTitle());
                break;
            case THINKING_UPDATE:
                sink.updateThinking(id, event.getText());
                break;
            case THINKING_FINISHED:
                sink.finishThinking(id, event.getText());
                break;
            case TOOL_STARTED:
                sink.startToolActivity(id, event.getTitle(), event.getText());
                break;
            case TOOL_UPDATE:
                sink.updateToolActivity(id, event.getTitle(), event.getText());
                break;
            case TOOL_COMPLETED:
                sink.completeToolActivity(id, event.getText());
                break;
            case TOOL_FAILED:
                sink.failToolActivity(id, event.getText());
                break;
            case APPROVAL_REQUIRED:
                sink.requestApproval(id, event.getText());
                break;
            default:
                break;
        }
    }

    /**
     * Single source of command availability: the allowed set of the live state (productive mode reads the
     * authoritative resources state). This is the "available research actions" projection the UI renders —
     * it never re-implements phase rules.
     */
    private List<String> allowedCommandNames() {
        java.util.Set<ResearchCommandType> allowed = currentAllowedCommands();
        List<String> names = new ArrayList<String>();
        names.add("status");
        names.add("open");
        if (allowed.contains(ResearchCommandType.PAUSE)) {
            names.add("pause");
        }
        if (allowed.contains(ResearchCommandType.RESUME)) {
            names.add("resume");
        }
        if (allowed.contains(ResearchCommandType.CANCEL)) {
            names.add("cancel");
        }
        boolean approvable = state.getPendingApprovalId() != null;
        for (ResearchCommandType type : allowed) {
            if (type.name().startsWith("APPROVE_") || type.name().startsWith("REQUEST_")) {
                approvable = true;
            }
        }
        if (approvable) {
            names.add("approve");
            names.add("request-changes");
        }
        // Every remaining allowed forward command is offered by name for /do (kebab-case).
        for (ResearchCommandType type : allowed) {
            String kebab = type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            if (!names.contains(kebab) && type != ResearchCommandType.PAUSE
                    && type != ResearchCommandType.RESUME && type != ResearchCommandType.CANCEL) {
                names.add(kebab);
            }
        }
        return names;
    }

    /** The composer route: plain prompts go to the backend; stop pauses the run. */
    private final class ResearchChatTarget implements ChatSubmissionTarget {
        public SubmissionAvailability getAvailability() {
            String stateId = state.getStateId();
            boolean terminal = com.aresstack.askai.research.state.oo.ResearchStateIds.isTerminal(stateId);
            if (disposed || handle == null || terminal) {
                return SubmissionAvailability.UNAVAILABLE;
            }
            if (productiveResources != null) {
                // Productive mode: "running" is the PHASE (research stays active between turns) — the
                // composer is busy only while an agent TURN is actually in flight. Otherwise the user
                // could never type again after "Agent turn completed".
                return agentTurnInFlight ? SubmissionAvailability.BUSY
                        : SubmissionAvailability.AVAILABLE;
            }
            return com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(stateId)
                    ? SubmissionAvailability.BUSY : SubmissionAvailability.AVAILABLE;
        }

        public void submitText(String text) {
            if (text != null && !text.trim().isEmpty()) {
                submitPrompt(text, "");
            }
        }

        public void stop() {
            pause();
        }
    }
}
