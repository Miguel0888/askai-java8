package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable, read-only view of the hierarchical research state for the State visualization. It is derived
 * ONLY from the domain (a {@link ResearchPhaseState}) plus the revision and an optional problem reason — never
 * from UI flags or chat text. {@link #getAllowedCommands()} comes straight from the state object, the same
 * source slash-command gating uses, so the view carries no transition table of its own.
 */
public final class ResearchStateSnapshot {

    private static final List<String> PHASE_ORDER = Collections.unmodifiableList(Arrays.asList(
            ResearchStateIds.SCOPING, ResearchStateIds.OUTLINE, ResearchStateIds.RESEARCH,
            ResearchStateIds.EVIDENCE, ResearchStateIds.DRAFT, ResearchStateIds.REVIEW,
            ResearchStateIds.FINALIZATION));

    private final String currentPhaseId;
    private final String currentStateId;
    private final String continuationStateId;
    private final String pendingApprovalId;
    private final long revision;
    private final boolean terminal;
    private final String problem;
    private final Set<ResearchCommandType> allowedCommands;

    private ResearchStateSnapshot(ResearchPhaseState phase, long revision, String problem) {
        this.currentPhaseId = phase.getPhaseId();
        this.currentStateId = phase.getCurrentState().getStateId();
        this.continuationStateId = phase.getCurrentState().getContinuationStateId();
        this.pendingApprovalId = phase.getCurrentState().getPendingApprovalId();
        this.revision = revision;
        this.terminal = phase.getCurrentState().isTerminal();
        this.problem = problem == null ? "" : problem;
        this.allowedCommands = Collections.unmodifiableSet(
                new LinkedHashSet<ResearchCommandType>(phase.getAllowedCommands()));
    }

    public static ResearchStateSnapshot of(ResearchPhaseState phase, long revision, String problem) {
        return new ResearchStateSnapshot(phase, revision, problem);
    }

    public List<String> getPhaseOrder() {
        return PHASE_ORDER;
    }

    public String getCurrentPhaseId() {
        return currentPhaseId;
    }

    public String getCurrentStateId() {
        return currentStateId;
    }

    public String getContinuationStateId() {
        return continuationStateId;
    }

    public String getPendingApprovalId() {
        return pendingApprovalId;
    }

    public long getRevision() {
        return revision;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public String getProblem() {
        return problem;
    }

    public Set<ResearchCommandType> getAllowedCommands() {
        return allowedCommands;
    }

    /** @return phases before the current one (treated as completed for the timeline). */
    public List<String> getCompletedPhaseIds() {
        List<String> done = new ArrayList<String>();
        int currentIndex = PHASE_ORDER.indexOf(currentPhaseId);
        for (int i = 0; i < currentIndex; i++) {
            done.add(PHASE_ORDER.get(i));
        }
        if (ResearchStateIds.COMPLETED.equals(currentStateId)) {
            done.add(currentPhaseId);
        }
        return done;
    }
}
