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
public final class ResearchAgentSession implements AgentSession, ResearchSessionListener {

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
    private boolean disposed;

    /**
     * @param ownedScheduler a scheduler this session must shut down on {@link #close()} (the production path),
     *                        or {@code null} when the scheduler is owned elsewhere (tests inject their own).
     */
    public ResearchAgentSession(ResearchSessionBackend backend, ResearchScheduler ownedScheduler,
                                AgentHostContext host, String sessionId, String projectId) {
        this.backend = backend;
        this.ownedScheduler = ownedScheduler;
        this.sink = host.getConversationSink();
        this.uiExecutor = host.getUiExecutor();
        this.request = new ResearchProjectRequest(sessionId, projectId, "Research project");
    }

    // ------------------------------------------------------------------ AgentSession lifecycle

    @Override
    public void activate() {
        if (disposed || started) {
            return;
        }
        // Mark started BEFORE createSession: the backend emits the initial START event synchronously, so the
        // listener must already accept it even though the handle field is assigned only when the call returns.
        started = true;
        handle = backend.createSession(request, this);
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
        if (handle != null) {
            backend.close(handle);
            handle = null;
        }
        if (ownedScheduler != null) {
            ownedScheduler.shutdown();
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
        return artifactStore;
    }

    /** Plugin-internal accessor (same classloader): the structured sources repository for the sources view. */
    public com.aresstack.askai.research.sources.ResearchSourceRepository getSourceRepository() {
        return sourceRepository;
    }

    @Override
    public AgentStateSnapshot getState() {
        ResearchPhase phase = com.aresstack.askai.research.state.oo.ResearchStateIds.phase(state.getPhaseId());
        ResearchRunState run =
                com.aresstack.askai.research.state.oo.ResearchStateIds.runState(state.getStateId());
        boolean busy = com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(state.getStateId());
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

    public void submitPrompt(String text, String activeSectionId) {
        if (handle != null) {
            backend.submitPrompt(handle, new ResearchPrompt(text, activeSectionId));
        }
    }

    public void approveCurrent() {
        String pendingApprovalId = state.getPendingApprovalId();
        if (handle != null && pendingApprovalId != null) {
            backend.approve(handle, pendingApprovalId);
        }
    }

    public void requestChanges(String reason) {
        String pendingApprovalId = state.getPendingApprovalId();
        if (handle != null && pendingApprovalId != null) {
            backend.reject(handle, pendingApprovalId, reason);
        }
    }

    public void pause() {
        if (handle != null) {
            backend.pause(handle);
        }
    }

    public void resume() {
        if (handle != null) {
            backend.resume(handle);
        }
    }

    public void cancel() {
        if (handle != null) {
            backend.cancel(handle);
        }
    }

    public boolean canDispatch(ResearchCommandType type) {
        return handle != null
                && stateFactory.restore(state).getCurrentState().getAllowedCommands().contains(type);
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
            case ASSISTANT_MESSAGE:
            case COMPLETED:
                sink.appendAssistantMessage(event.getEventId(), event.getText());
                break;
            case BLOCKED:
            case ERROR:
                problemMessage = event.getPublicMessage();
                sink.showProblem(event.getEventId(), event.getPublicMessage());
                break;
            default:
                break; // SOURCE_ADDED/FINDING_ADDED/OUTLINE_CHANGED/PROBLEM_REPORTED handled by artifact views
        }
        fireStateChanged(); // the State visualization re-reads the domain snapshot
    }

    // ------------------------------------------------------------------ state visualization support

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
            listener.run();
        }
    }

    /**
     * A read-only snapshot of the hierarchical state, rebuilt from the live {@link ResearchStateMemento} — the
     * exact phase/state/continuation/approval id, never a defaulted continuation.
     */
    public ResearchStateSnapshot currentResearchSnapshot() {
        return ResearchStateSnapshot.of(stateFactory.restore(state), revision, problemMessage);
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

    /** Single source of command availability: the allowed set of the live memento's current state. */
    private List<String> allowedCommandNames() {
        java.util.Set<ResearchCommandType> allowed =
                stateFactory.restore(state).getCurrentState().getAllowedCommands();
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
        if (state.getPendingApprovalId() != null) {
            names.add("approve");
            names.add("request-changes");
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
