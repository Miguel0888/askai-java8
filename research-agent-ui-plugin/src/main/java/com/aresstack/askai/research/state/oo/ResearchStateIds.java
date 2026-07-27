package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

/**
 * Stable string ids for the hierarchical OO state model, plus the bidirectional mapping to the legacy
 * {@link ResearchPhase}/{@link ResearchRunState} pair used by the {@code ResearchStateMachine} port. Only ids
 * are persisted (never state objects); this class is the single place that knows both vocabularies.
 *
 * <p>The two-dimensional legacy pair flattens two distinct waiting sub-states onto {@code WAITING_FOR_USER}:
 * an approval gate and a "ready to start the next step" gate. Which one a phase uses is fixed per phase, so the
 * mapping is deterministic in both directions.</p>
 */
public final class ResearchStateIds {

    // Phase ids.
    public static final String SCOPING = "scoping";
    public static final String OUTLINE = "outline";
    public static final String RESEARCH = "research";
    public static final String EVIDENCE = "evidence";
    public static final String DRAFT = "draft";
    public static final String REVIEW = "review";
    public static final String FINALIZATION = "finalization";

    // State ids.
    public static final String NEW = "new";
    public static final String RUNNING = "running";
    public static final String WAITING = "waiting";               // ready-to-start gate (RESEARCH, DRAFT)
    public static final String WAITING_APPROVAL = "waiting_approval"; // human approval gate
    public static final String PAUSED = "paused";
    public static final String BLOCKED = "blocked";
    public static final String FAILED = "failed";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    private ResearchStateIds() {
    }

    public static String phaseId(ResearchPhase phase) {
        switch (phase) {
            case SCOPING: return SCOPING;
            case OUTLINE: return OUTLINE;
            case RESEARCH: return RESEARCH;
            case EVIDENCE: return EVIDENCE;
            case DRAFT: return DRAFT;
            case REVIEW: return REVIEW;
            case FINALIZATION: return FINALIZATION;
            default: throw new IllegalArgumentException("unknown phase: " + phase);
        }
    }

    public static ResearchPhase phase(String phaseId) {
        if (SCOPING.equals(phaseId)) return ResearchPhase.SCOPING;
        if (OUTLINE.equals(phaseId)) return ResearchPhase.OUTLINE;
        if (RESEARCH.equals(phaseId)) return ResearchPhase.RESEARCH;
        if (EVIDENCE.equals(phaseId)) return ResearchPhase.EVIDENCE;
        if (DRAFT.equals(phaseId)) return ResearchPhase.DRAFT;
        if (REVIEW.equals(phaseId)) return ResearchPhase.REVIEW;
        if (FINALIZATION.equals(phaseId)) return ResearchPhase.FINALIZATION;
        throw new IllegalArgumentException("unknown phaseId: " + phaseId);
    }

    /** @return whether this phase's {@code WAITING_FOR_USER} means "human approval gate". */
    public static boolean isApprovalPhase(String phaseId) {
        return OUTLINE.equals(phaseId) || EVIDENCE.equals(phaseId)
                || REVIEW.equals(phaseId) || FINALIZATION.equals(phaseId);
    }

    /** Legacy (phase, runState) → OO state id. */
    public static String stateId(String phaseId, ResearchRunState runState) {
        switch (runState) {
            case NEW: return NEW;
            case RUNNING: return RUNNING;
            case WAITING_FOR_USER: return isApprovalPhase(phaseId) ? WAITING_APPROVAL : WAITING;
            case PAUSED: return PAUSED;
            case BLOCKED: return BLOCKED;
            case FAILED: return FAILED;
            case COMPLETED: return COMPLETED;
            case CANCELLED: return CANCELLED;
            default: throw new IllegalArgumentException("unknown runState: " + runState);
        }
    }

    /** OO state id → legacy runState. */
    public static ResearchRunState runState(String stateId) {
        if (NEW.equals(stateId)) return ResearchRunState.NEW;
        if (RUNNING.equals(stateId)) return ResearchRunState.RUNNING;
        if (WAITING.equals(stateId) || WAITING_APPROVAL.equals(stateId)) return ResearchRunState.WAITING_FOR_USER;
        if (PAUSED.equals(stateId)) return ResearchRunState.PAUSED;
        if (BLOCKED.equals(stateId)) return ResearchRunState.BLOCKED;
        if (FAILED.equals(stateId)) return ResearchRunState.FAILED;
        if (COMPLETED.equals(stateId)) return ResearchRunState.COMPLETED;
        if (CANCELLED.equals(stateId)) return ResearchRunState.CANCELLED;
        throw new IllegalArgumentException("unknown stateId: " + stateId);
    }

    public static boolean isTerminal(String stateId) {
        return COMPLETED.equals(stateId) || CANCELLED.equals(stateId);
    }
}
