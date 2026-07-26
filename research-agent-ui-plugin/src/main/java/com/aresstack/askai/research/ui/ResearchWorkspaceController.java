package com.aresstack.askai.research.ui;

import com.aresstack.askai.research.demo.ResearchDemoData;
import com.aresstack.askai.research.domain.ResearchFinding;
import com.aresstack.askai.research.domain.ResearchOutline;
import com.aresstack.askai.research.domain.ResearchProblem;
import com.aresstack.askai.research.domain.ResearchSection;
import com.aresstack.askai.research.domain.ResearchSource;
import com.aresstack.askai.research.state.DefaultResearchStateMachine;
import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;
import com.aresstack.askai.research.state.ResearchSessionState;
import com.aresstack.askai.research.state.ResearchStateMachine;
import com.aresstack.askai.research.state.ResearchTransitionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the research workspace against the real {@link ResearchStateMachine} over a local state (Commit 7).
 * The UI reads the current state, queries which commands are legal (for button enablement), and dispatches
 * commands here; an illegal command is a no-op and never changes the state. It also holds the static demo
 * outline/sources/findings/problems and the active-section filter. Commit 8 swaps the local dispatch for the
 * event-driven fake backend without changing the UI.
 */
public final class ResearchWorkspaceController {

    private final ResearchStateMachine stateMachine;
    private final AtomicLong commandSequence = new AtomicLong();
    private final List<Runnable> listeners = new ArrayList<Runnable>();

    private ResearchSessionState state = ResearchSessionState.initial();
    private ResearchOutline outline = ResearchDemoData.outline();
    private final List<ResearchSource> sources = ResearchDemoData.sources();
    private final List<ResearchFinding> findings = ResearchDemoData.findings();
    private final List<ResearchProblem> problems = ResearchDemoData.problems();
    private String activeSectionId = "";

    public ResearchWorkspaceController(String sessionId) {
        this.stateMachine = new DefaultResearchStateMachine(sessionId);
    }

    // ------------------------------------------------------------------ state + commands

    public ResearchSessionState getState() {
        return state;
    }

    public boolean canDispatch(ResearchCommandType type) {
        return stateMachine.dispatch(state, command(type)).isAccepted();
    }

    /** @return true if the command was accepted and applied; false (no-op) for an illegal command. */
    public boolean dispatch(ResearchCommandType type) {
        ResearchTransitionResult result = stateMachine.dispatch(state, command(type));
        if (result.isAccepted()) {
            state = result.getState();
            fireChange();
            return true;
        }
        return false;
    }

    private ResearchCommand command(ResearchCommandType type) {
        return ResearchCommand.of(type, type + "-" + commandSequence.incrementAndGet());
    }

    // ------------------------------------------------------------------ outline

    public ResearchOutline getOutline() {
        return outline;
    }

    /** Outline edits are allowed while the session is not terminal and not paused. */
    public boolean canEditOutline() {
        return !state.getRunState().isTerminal() && state.getRunState() != ResearchRunState.PAUSED;
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

    // ------------------------------------------------------------------ active section + filters

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

    // Convenience for the phase bar.
    public ResearchPhase phase() {
        return state.getPhase();
    }

    public ResearchRunState runState() {
        return state.getRunState();
    }

    // ------------------------------------------------------------------ context commands for the toolbar

    /** The APPROVE_* command valid in the current state, or {@code null}. */
    public ResearchCommandType approveCommand() {
        if (state.getRunState() != ResearchRunState.WAITING_FOR_USER) {
            return null;
        }
        switch (state.getPhase()) {
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

    /** The "request changes / revision" command valid in the current state, or {@code null}. */
    public ResearchCommandType requestChangesCommand() {
        if (state.getRunState() != ResearchRunState.WAITING_FOR_USER) {
            return null;
        }
        switch (state.getPhase()) {
            case OUTLINE:
                return ResearchCommandType.REQUEST_OUTLINE_CHANGES;
            case EVIDENCE:
            case REVIEW:
                return ResearchCommandType.REQUEST_REVISION;
            default:
                return null;
        }
    }

    /** The next natural forward command in the happy path for the current state, or {@code null}. */
    public ResearchCommandType nextStepCommand() {
        ResearchPhase phase = state.getPhase();
        ResearchRunState run = state.getRunState();
        if (run == ResearchRunState.NEW && phase == ResearchPhase.SCOPING) {
            return ResearchCommandType.START;
        }
        if (run != ResearchRunState.RUNNING && run != ResearchRunState.WAITING_FOR_USER) {
            return null;
        }
        switch (phase) {
            case SCOPING:
                return run == ResearchRunState.RUNNING ? ResearchCommandType.SUBMIT_SCOPE : null;
            case OUTLINE:
                return run == ResearchRunState.RUNNING ? ResearchCommandType.PROPOSE_OUTLINE : null;
            case RESEARCH:
                return run == ResearchRunState.WAITING_FOR_USER
                        ? ResearchCommandType.START_RESEARCH : ResearchCommandType.REQUEST_EVIDENCE_REVIEW;
            case DRAFT:
                return run == ResearchRunState.WAITING_FOR_USER
                        ? ResearchCommandType.START_DRAFTING : ResearchCommandType.REQUEST_DRAFT_REVIEW;
            case FINALIZATION:
                return run == ResearchRunState.RUNNING ? ResearchCommandType.REQUEST_FINAL_REVIEW : null;
            default:
                return null;
        }
    }
}
