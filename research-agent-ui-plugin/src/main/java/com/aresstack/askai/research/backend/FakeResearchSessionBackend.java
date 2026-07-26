package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.DefaultResearchStateMachine;
import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;
import com.aresstack.askai.research.state.ResearchSessionState;
import com.aresstack.askai.research.state.ResearchStateMachine;
import com.aresstack.askai.research.state.ResearchTransitionResult;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deterministic, event-driven simulation of a research agent behind {@link ResearchSessionBackend}. It
 * never invents functional states: every phase change goes through {@link DefaultResearchStateMachine}. The
 * run advances via the injected {@link ResearchScheduler}; per session, events are delivered serially with a
 * monotonic sequence number. Pause halts delivery (the logical progression resumes exactly where it stopped,
 * no duplicate scheduling), approvals are real wait states, cancel is a functional CANCEL, and close releases
 * resources so no listener call ever happens afterward. {@code simulateBlocked}/{@code simulateFailure} are
 * dev actions producing recoverable BLOCKED and technical FAILED states.
 */
public final class FakeResearchSessionBackend implements ResearchSessionBackend {

    private final ResearchScheduler scheduler;
    private final ResearchClock clock;
    private final ResearchIdGenerator idGenerator;
    private final long stepDelayMillis;
    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<String, FakeSession>();

    public FakeResearchSessionBackend(ResearchScheduler scheduler, ResearchClock clock,
                                      ResearchIdGenerator idGenerator, long stepDelayMillis) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.stepDelayMillis = stepDelayMillis;
    }

    @Override
    public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                               ResearchSessionListener listener) {
        FakeSession session = new FakeSession(request.getSessionId(), request.getProjectId(), listener);
        sessions.put(session.sessionId, session);
        synchronized (session) {
            dispatch(session, ResearchCommandType.START, null);
            scheduleAdvance(session);
        }
        return session;
    }

    @Override
    public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return false;
        }
        synchronized (session) {
            return session.stateMachine.dispatch(session.state, command(command)).isAccepted();
        }
    }

    @Override
    public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, command, null)) {
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.USER_MESSAGE)
                    .text(prompt.getText()), null);
            ResearchRunState run = session.state.getRunState();
            if (run == ResearchRunState.PAUSED || run == ResearchRunState.CANCELLED
                    || run == ResearchRunState.FAILED || run == ResearchRunState.COMPLETED) {
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                        .text("The agent is " + run + " and cannot act on this prompt right now."), null);
                return;
            }
            String section = prompt.getActiveSectionId().isEmpty()
                    ? "the whole document" : "section " + prompt.getActiveSectionId();
            String activityId = idGenerator.newId();
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                    .activity(activityId, ResearchActivityKind.THINKING_STARTED, "Thinking",
                            "Interpreting the request about " + section), null);
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                    .activity(activityId, ResearchActivityKind.THINKING_FINISHED, "Thinking", "Ready"), null);
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                    .text("Noted for " + section + ": " + prompt.getText()), null);
        }
    }

    @Override
    public void approve(ResearchSessionHandle handle, String approvalId) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (session.processedApprovals.contains(approvalId)) {
                return; // idempotent
            }
            if (session.pendingApprovalId == null || !session.pendingApprovalId.equals(approvalId)) {
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("Unknown or stale approval.", "approvalId=" + approvalId), null);
                return;
            }
            ResearchCommandType approveCommand = approveCommandFor(session.state.getPhase());
            session.processedApprovals.add(approvalId);
            session.pendingApprovalId = null;
            if (approveCommand != null && dispatch(session, approveCommand, null)) {
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (session.processedApprovals.contains(approvalId)) {
                return;
            }
            if (session.pendingApprovalId == null || !session.pendingApprovalId.equals(approvalId)) {
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("Unknown or stale approval.", "approvalId=" + approvalId), null);
                return;
            }
            ResearchCommandType changesCommand = requestChangesCommandFor(session.state.getPhase());
            session.processedApprovals.add(approvalId);
            session.pendingApprovalId = null;
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                    .text("Changes requested: " + (reason == null ? "" : reason)), null);
            if (changesCommand != null && dispatch(session, changesCommand, null)) {
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void pause(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.PAUSE, null)) {
                cancelPending(session); // no further scheduled progress; the logical position is preserved
            }
        }
    }

    @Override
    public void resume(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.RESUME, null)) {
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void cancel(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            cancelPending(session);
            dispatch(session, ResearchCommandType.CANCEL, null); // functional cancel; still readable until close
        }
    }

    @Override
    public void close(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (session.closed) {
                return; // idempotent
            }
            session.closed = true;
            cancelPending(session);
            session.listener = null; // no listener call can happen after close
        }
        sessions.remove(session.sessionId);
    }

    /** Dev action: put the session into a recoverable BLOCKED state with a visible reason. */
    public void simulateBlocked(ResearchSessionHandle handle, String reason) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.BLOCK, null)) {
                cancelPending(session);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.BLOCKED)
                        .messages(reason == null ? "Blocked." : reason, ""), null);
            }
        }
    }

    /** Dev action: fail the session with a public message plus a (non-public-facing) technical detail. */
    public void simulateFailure(ResearchSessionHandle handle, String publicMessage, String technicalDetail) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.FAIL, null)) {
                cancelPending(session);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages(publicMessage == null ? "The run failed." : publicMessage,
                                technicalDetail == null ? "" : technicalDetail), null);
            }
        }
    }

    public void shutdown() {
        for (FakeSession session : sessions.values()) {
            close(session);
        }
        scheduler.shutdown();
    }

    // ------------------------------------------------------------------ automatic progression

    private void scheduleAdvance(final FakeSession session) {
        if (!canAdvance(session)) {
            return;
        }
        cancelPending(session);
        session.pending = scheduler.schedule(new Runnable() {
            public void run() {
                synchronized (session) {
                    session.pending = null;
                    if (!canAdvance(session)) {
                        return;
                    }
                    advanceOneStep(session);
                    scheduleAdvance(session);
                }
            }
        }, stepDelayMillis);
    }

    private boolean canAdvance(FakeSession session) {
        if (session.closed) {
            return false;
        }
        ResearchRunState run = session.state.getRunState();
        if (run == ResearchRunState.PAUSED || run == ResearchRunState.CANCELLED
                || run == ResearchRunState.FAILED || run == ResearchRunState.BLOCKED
                || run == ResearchRunState.COMPLETED) {
            return false;
        }
        // An approval gate (WAITING with a pending approval) stops automatic progress.
        return session.pendingApprovalId == null;
    }

    private void advanceOneStep(FakeSession session) {
        ResearchPhase phase = session.state.getPhase();
        ResearchRunState run = session.state.getRunState();
        switch (phase) {
            case SCOPING:
                if (run == ResearchRunState.RUNNING) {
                    thinking(session, "Scoping the request");
                    dispatch(session, ResearchCommandType.SUBMIT_SCOPE, null);
                }
                break;
            case OUTLINE:
                if (run == ResearchRunState.RUNNING) {
                    toolRun(session, "Draft outline", "Proposing sections");
                    dispatch(session, ResearchCommandType.PROPOSE_OUTLINE, null);
                    raiseApproval(session, "Approve the proposed outline?");
                }
                break;
            case RESEARCH:
                if (run == ResearchRunState.WAITING_FOR_USER) {
                    dispatch(session, ResearchCommandType.START_RESEARCH, null); // auto-start after approval
                } else if (run == ResearchRunState.RUNNING) {
                    toolRun(session, "Search web", "Capturing sources");
                    emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.SOURCE_ADDED)
                            .title("Captured source"), null);
                    emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.FINDING_ADDED)
                            .title("Extracted finding"), null);
                    dispatch(session, ResearchCommandType.REQUEST_EVIDENCE_REVIEW, null);
                    raiseApproval(session, "Approve the collected evidence?");
                }
                break;
            case EVIDENCE:
                // WAITING here is the approval gate (handled by raiseApproval); nothing automatic.
                break;
            case DRAFT:
                if (run == ResearchRunState.WAITING_FOR_USER) {
                    dispatch(session, ResearchCommandType.START_DRAFTING, null);
                } else if (run == ResearchRunState.RUNNING) {
                    assistant(session, "Drafting the sections.");
                    dispatch(session, ResearchCommandType.REQUEST_DRAFT_REVIEW, null);
                    raiseApproval(session, "Approve the draft?");
                }
                break;
            case REVIEW:
                break;
            case FINALIZATION:
                if (run == ResearchRunState.RUNNING) {
                    dispatch(session, ResearchCommandType.REQUEST_FINAL_REVIEW, null);
                    raiseApproval(session, "Approve the final document?");
                }
                break;
            default:
                break;
        }
    }

    private void raiseApproval(FakeSession session, String prompt) {
        if (session.state.getRunState() == ResearchRunState.WAITING_FOR_USER
                && isApprovalPhase(session.state.getPhase())) {
            session.pendingApprovalId = idGenerator.newId();
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.APPROVAL_REQUESTED)
                    .approval(session.pendingApprovalId, prompt), null);
        }
    }

    private void thinking(FakeSession session, String what) {
        String id = idGenerator.newId();
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.THINKING_STARTED, "Thinking", what), null);
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.THINKING_FINISHED, "Thinking", "Done"), null);
    }

    private void toolRun(FakeSession session, String tool, String detail) {
        String id = idGenerator.newId();
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.TOOL_STARTED, tool, detail), null);
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.TOOL_COMPLETED, tool, "Completed"), null);
    }

    private void assistant(FakeSession session, String text) {
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .text(text), null);
    }

    // ------------------------------------------------------------------ state machine + emission

    private boolean dispatch(FakeSession session, ResearchCommandType type, String commandId) {
        ResearchCommand cmd = command(type);
        ResearchTransitionResult result = session.stateMachine.dispatch(session.state, cmd);
        if (!result.isAccepted()) {
            return false;
        }
        session.state = result.getState();
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.SESSION_STATE_CHANGED)
                .state(session.state.getPhase(), session.state.getRunState()),
                commandId == null ? cmd.getCommandId() : commandId);
        if (session.state.getRunState() == ResearchRunState.COMPLETED) {
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                    .text("Research completed."), null);
        }
        return true;
    }

    private ResearchCommand command(ResearchCommandType type) {
        return ResearchCommand.of(type, type + "-" + idGenerator.newId());
    }

    /** Serial, sequence-numbered emission; never fires after close. */
    private void emit(FakeSession session, ResearchBackendEvent.Builder builder, String commandId) {
        if (session.closed || session.listener == null) {
            return;
        }
        long seq = ++session.sequence;
        ResearchBackendEvent event = builder
                .envelope(idGenerator.newId(), session.sessionId, session.projectId,
                        session.state.getRevision(), clock.now(), seq, commandId)
                .build();
        session.listener.onEvent(event);
    }

    private void cancelPending(FakeSession session) {
        if (session.pending != null) {
            session.pending.cancel();
            session.pending = null;
        }
    }

    private FakeSession resolve(ResearchSessionHandle handle) {
        return handle == null ? null : sessions.get(handle.getSessionId());
    }

    private static boolean isApprovalPhase(ResearchPhase phase) {
        return phase == ResearchPhase.OUTLINE || phase == ResearchPhase.EVIDENCE
                || phase == ResearchPhase.REVIEW || phase == ResearchPhase.FINALIZATION;
    }

    private static ResearchCommandType approveCommandFor(ResearchPhase phase) {
        switch (phase) {
            case OUTLINE:
                return ResearchCommandType.APPROVE_OUTLINE;
            case EVIDENCE:
                return ResearchCommandType.APPROVE_EVIDENCE;
            case REVIEW:
                return ResearchCommandType.APPROVE_DRAFT;
            case FINALIZATION:
                return ResearchCommandType.APPROVE_FINAL;
            default:
                return null;
        }
    }

    private static ResearchCommandType requestChangesCommandFor(ResearchPhase phase) {
        switch (phase) {
            case OUTLINE:
                return ResearchCommandType.REQUEST_OUTLINE_CHANGES;
            case EVIDENCE:
            case REVIEW:
                return ResearchCommandType.REQUEST_REVISION;
            default:
                return null;
        }
    }

    /** One isolated session: its own state machine, sequence, pending task and approval wait point. */
    private static final class FakeSession implements ResearchSessionHandle {
        private final String sessionId;
        private final String projectId;
        private ResearchSessionListener listener;
        private final ResearchStateMachine stateMachine;
        private ResearchSessionState state = ResearchSessionState.initial();
        private long sequence;
        private boolean closed;
        private String pendingApprovalId;
        private final Set<String> processedApprovals = new HashSet<String>();
        private ResearchScheduler.Cancellable pending;

        private FakeSession(String sessionId, String projectId, ResearchSessionListener listener) {
            this.sessionId = sessionId;
            this.projectId = projectId;
            this.listener = listener;
            this.stateMachine = new DefaultResearchStateMachine(sessionId);
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getProjectId() {
            return projectId;
        }
    }
}
