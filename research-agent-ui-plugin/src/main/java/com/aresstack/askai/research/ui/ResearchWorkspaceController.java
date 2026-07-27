package com.aresstack.askai.research.ui;

import com.aresstack.askai.plugin.api.service.ConversationSurface;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.demo.ResearchDemoData;
import com.aresstack.askai.research.domain.ResearchFinding;
import com.aresstack.askai.research.domain.ResearchOutline;
import com.aresstack.askai.research.domain.ResearchProblem;
import com.aresstack.askai.research.domain.ResearchSection;
import com.aresstack.askai.research.domain.ResearchSource;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the research UI to the {@link ResearchSessionBackend}. It owns no session state machine: the UI
 * sends commands/prompts/approvals here, they go to the backend, and backend events are marshalled onto the
 * EDT via {@link UiExecutor} and applied to a small view-model that the panels read. Late/duplicate/foreign
 * events are dropped (guards: not disposed, session id current, sequence strictly newer). The outline shown
 * for the tables is static demo data filtered by the active section.
 */
public final class ResearchWorkspaceController implements ResearchSessionListener {

    private final ResearchSessionBackend backend;
    private final UiExecutor uiExecutor;
    private final ConversationSurface conversation;
    private final ResearchProjectRequest request;
    private final List<Runnable> listeners = new ArrayList<Runnable>();

    // View-model (updated only on the EDT from backend events).
    private ResearchPhase phase = ResearchPhase.SCOPING;
    private ResearchRunState runState = ResearchRunState.NEW;
    private String pendingApprovalId;
    private long lastSequence = -1L;

    private ResearchOutline outline = ResearchDemoData.outline();
    private final List<ResearchSource> sources = ResearchDemoData.sources();
    private final List<ResearchFinding> findings = ResearchDemoData.findings();
    private final List<ResearchProblem> problems = ResearchDemoData.problems();
    private String activeSectionId = "";

    private ResearchSessionHandle handle;
    private boolean started;
    private boolean disposed;

    public ResearchWorkspaceController(ResearchSessionBackend backend, UiExecutor uiExecutor,
                                       ConversationSurface conversation, String sessionId, String projectId) {
        this.backend = backend;
        this.uiExecutor = uiExecutor;
        this.conversation = conversation;
        this.request = new ResearchProjectRequest(sessionId, projectId, "Research project");
    }

    /** Opens the backend session and starts the simulated run. Idempotent. */
    public void start() {
        if (disposed || started) {
            return;
        }
        // Mark started BEFORE createSession: the backend emits the initial START event synchronously, so the
        // listener must already accept it even though the handle field is assigned only when the call returns.
        started = true;
        handle = backend.createSession(request, this);
    }

    public void dispose() {
        disposed = true;
        if (handle != null) {
            backend.close(handle);
            handle = null;
        }
    }

    // ------------------------------------------------------------------ event intake (backend thread → EDT)

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
            return; // workspace closed or a different session while queued
        }
        if (event.getSequenceNumber() <= lastSequence) {
            return; // stale or duplicate delivery
        }
        lastSequence = event.getSequenceNumber();
        switch (event.getType()) {
            case SESSION_STATE_CHANGED:
                phase = event.getPhase();
                runState = event.getRunState();
                if (runState != ResearchRunState.WAITING_FOR_USER) {
                    pendingApprovalId = null;
                }
                break;
            case APPROVAL_REQUESTED:
                pendingApprovalId = event.getApprovalId();
                conversation.addAssistantMessage(event.getEventId(),
                        "Approval required: " + event.getText());
                break;
            case ACTIVITY:
                applyActivity(event);
                break;
            case USER_MESSAGE:
                conversation.addUserMessage(event.getEventId(), event.getText());
                break;
            case ASSISTANT_MESSAGE:
            case COMPLETED:
                conversation.addAssistantMessage(event.getEventId(), event.getText());
                break;
            case BLOCKED:
            case ERROR:
                // Only the public message reaches the surface; the technical detail stays out.
                conversation.addAssistantMessage(event.getEventId(), event.getPublicMessage());
                break;
            default:
                break; // SOURCE_ADDED/FINDING_ADDED/OUTLINE_CHANGED/PROBLEM_REPORTED: tables use demo data
        }
        fireChange();
    }

    private void applyActivity(ResearchBackendEvent event) {
        String id = event.getActivityId();
        switch (event.getActivityKind()) {
            case THINKING_STARTED:
                conversation.startThinking(id, event.getTitle());
                break;
            case THINKING_UPDATE:
                conversation.updateThinking(id, event.getText());
                break;
            case THINKING_FINISHED:
                conversation.finishThinking(id, event.getText());
                break;
            case TOOL_STARTED:
                conversation.startToolActivity(id, event.getTitle(), event.getText());
                break;
            case TOOL_UPDATE:
                conversation.updateToolActivity(id, event.getTitle(), event.getText());
                break;
            case TOOL_COMPLETED:
                conversation.completeToolActivity(id, event.getText());
                break;
            case TOOL_FAILED:
                conversation.failToolActivity(id, event.getText());
                break;
            case APPROVAL_REQUIRED:
                conversation.markApprovalRequired(id, event.getText());
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ commands (UI → backend)

    public boolean canDispatch(ResearchCommandType type) {
        return handle != null && backend.canExecute(handle, type);
    }

    public void dispatch(ResearchCommandType type) {
        if (handle != null) {
            backend.executeCommand(handle, type);
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

    public boolean hasPendingApproval() {
        return pendingApprovalId != null;
    }

    public void approveCurrent() {
        if (handle != null && pendingApprovalId != null) {
            backend.approve(handle, pendingApprovalId);
        }
    }

    public void rejectCurrent(String reason) {
        if (handle != null && pendingApprovalId != null) {
            backend.reject(handle, pendingApprovalId, reason);
        }
    }

    public void submitPrompt(String text) {
        if (handle != null) {
            backend.submitPrompt(handle, new ResearchPrompt(text, activeSectionId));
        }
    }

    // ------------------------------------------------------------------ view-model reads

    public ResearchPhase phase() {
        return phase;
    }

    public ResearchRunState runState() {
        return runState;
    }

    public ResearchOutline getOutline() {
        return outline;
    }

    public boolean canEditOutline() {
        return !runState.isTerminal() && runState != ResearchRunState.PAUSED;
    }

    public boolean addSection(String parentId, String newId, String title) {
        return applyOutline(new OutlineEdit() {
            public ResearchOutline apply() {
                return outline.addSection(parentId, newId, title);
            }
        });
    }

    public boolean renameSection(String id, String title) {
        return applyOutline(new OutlineEdit() {
            public ResearchOutline apply() {
                return outline.renameSection(id, title);
            }
        });
    }

    public boolean moveSection(String id, int delta) {
        return applyOutline(new OutlineEdit() {
            public ResearchOutline apply() {
                return outline.reorderSection(id, delta);
            }
        });
    }

    public boolean removeSection(String id, ResearchOutline.ChildStrategy strategy) {
        return applyOutline(new OutlineEdit() {
            public ResearchOutline apply() {
                return outline.removeSection(id, strategy);
            }
        });
    }

    private boolean applyOutline(OutlineEdit edit) {
        if (!canEditOutline()) {
            return false;
        }
        try {
            outline = edit.apply();
            fireChange();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private interface OutlineEdit {
        ResearchOutline apply();
    }

    public String getActiveSectionId() {
        return activeSectionId;
    }

    public void setActiveSection(String sectionId) {
        this.activeSectionId = sectionId == null ? "" : sectionId;
        fireChange();
    }

    public List<ResearchSource> sourcesForActiveSection() {
        if (activeSectionId.isEmpty()) {
            return new ArrayList<ResearchSource>(sources);
        }
        List<ResearchSource> filtered = new ArrayList<ResearchSource>();
        for (ResearchSource s : sources) {
            if (s.getLinkedSectionIds().contains(activeSectionId)) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    public List<ResearchFinding> findingsForActiveSection() {
        if (activeSectionId.isEmpty()) {
            return new ArrayList<ResearchFinding>(findings);
        }
        List<ResearchFinding> filtered = new ArrayList<ResearchFinding>();
        for (ResearchFinding f : findings) {
            if (f.getLinkedSectionIds().contains(activeSectionId)) {
                filtered.add(f);
            }
        }
        return filtered;
    }

    public List<ResearchProblem> getProblems() {
        return new ArrayList<ResearchProblem>(problems);
    }

    public String documentMarkdown() {
        if (activeSectionId.isEmpty()) {
            return ResearchDemoData.documentMarkdown();
        }
        ResearchSection section = outline.section(activeSectionId);
        return ResearchDemoData.sectionMarkdown(section == null ? activeSectionId : section.getTitle());
    }

    public long documentRevision() {
        return outline.getRevision();
    }

    // ------------------------------------------------------------------ listeners

    public void addChangeListener(Runnable listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fireChange() {
        for (Runnable listener : new ArrayList<Runnable>(listeners)) {
            listener.run();
        }
    }
}
