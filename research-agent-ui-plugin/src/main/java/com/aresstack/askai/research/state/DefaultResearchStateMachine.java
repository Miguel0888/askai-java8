package com.aresstack.askai.research.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The canonical research lifecycle. Happy path:
 *
 * <pre>
 * SCOPING/NEW --START--> SCOPING/RUNNING --SUBMIT_SCOPE--> OUTLINE/RUNNING
 *   --PROPOSE_OUTLINE--> OUTLINE/WAITING_FOR_USER
 *   --APPROVE_OUTLINE--> RESEARCH/WAITING_FOR_USER --START_RESEARCH--> RESEARCH/RUNNING
 *   --REQUEST_EVIDENCE_REVIEW--> EVIDENCE/WAITING_FOR_USER
 *   --APPROVE_EVIDENCE--> DRAFT/WAITING_FOR_USER --START_DRAFTING--> DRAFT/RUNNING
 *   --REQUEST_DRAFT_REVIEW--> REVIEW/WAITING_FOR_USER
 *   --APPROVE_DRAFT--> FINALIZATION/RUNNING --REQUEST_FINAL_REVIEW--> FINALIZATION/WAITING_FOR_USER
 *   --APPROVE_FINAL--> FINALIZATION/COMPLETED
 * </pre>
 *
 * <p>REQUEST_OUTLINE_CHANGES (from OUTLINE/WAITING) and REQUEST_REVISION (from EVIDENCE or REVIEW/WAITING)
 * return to the matching working step. PAUSE/RESUME, BLOCK/UNBLOCK, FAIL/RETRY and CANCEL are orthogonal and
 * preserve the phase, so recovery resumes the same phase. Approval gates cannot be skipped: the next phase's
 * running state is reachable only via the corresponding APPROVE_*.</p>
 */
public final class DefaultResearchStateMachine implements ResearchStateMachine {

    private final String sessionId;
    private final IdGenerator idGenerator;
    private final TimeSource timeSource;

    public DefaultResearchStateMachine(String sessionId) {
        this(sessionId, new IdGenerator() {
            public String newId() {
                return UUID.randomUUID().toString();
            }
        }, new TimeSource() {
            public long now() {
                return System.currentTimeMillis();
            }
        });
    }

    public DefaultResearchStateMachine(String sessionId, IdGenerator idGenerator, TimeSource timeSource) {
        this.sessionId = sessionId;
        this.idGenerator = idGenerator;
        this.timeSource = timeSource;
    }

    @Override
    public ResearchTransitionResult dispatch(ResearchSessionState current, ResearchCommand command) {
        ResearchPhase phase = current.getPhase();
        ResearchRunState run = current.getRunState();
        switch (command.getType()) {
            case START:
                return require(current, phase == ResearchPhase.SCOPING && run == ResearchRunState.NEW,
                        ResearchPhase.SCOPING, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            case SUBMIT_SCOPE:
                return require(current, phase == ResearchPhase.SCOPING && run == ResearchRunState.RUNNING,
                        ResearchPhase.OUTLINE, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            case PROPOSE_OUTLINE:
                return require(current, phase == ResearchPhase.OUTLINE && run == ResearchRunState.RUNNING,
                        ResearchPhase.OUTLINE, ResearchRunState.WAITING_FOR_USER,
                        ResearchEventType.OutlineProposed, ResearchEventType.ApprovalRequested,
                        ResearchEventType.SessionStateChanged);
            case APPROVE_OUTLINE:
                return require(current, phase == ResearchPhase.OUTLINE && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.RESEARCH, ResearchRunState.WAITING_FOR_USER,
                        ResearchEventType.OutlineApproved, ResearchEventType.SessionStateChanged);
            case REQUEST_OUTLINE_CHANGES:
                return require(current, phase == ResearchPhase.OUTLINE && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.OUTLINE, ResearchRunState.RUNNING,
                        ResearchEventType.RevisionRequested, ResearchEventType.SessionStateChanged);
            case START_RESEARCH:
                return require(current, phase == ResearchPhase.RESEARCH && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.RESEARCH, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            case REQUEST_EVIDENCE_REVIEW:
                return require(current, phase == ResearchPhase.RESEARCH && run == ResearchRunState.RUNNING,
                        ResearchPhase.EVIDENCE, ResearchRunState.WAITING_FOR_USER,
                        ResearchEventType.ApprovalRequested, ResearchEventType.SessionStateChanged);
            case APPROVE_EVIDENCE:
                return require(current, phase == ResearchPhase.EVIDENCE && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.DRAFT, ResearchRunState.WAITING_FOR_USER,
                        ResearchEventType.SessionStateChanged);
            case START_DRAFTING:
                return require(current, phase == ResearchPhase.DRAFT && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.DRAFT, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            case REQUEST_DRAFT_REVIEW:
                return require(current, phase == ResearchPhase.DRAFT && run == ResearchRunState.RUNNING,
                        ResearchPhase.REVIEW, ResearchRunState.WAITING_FOR_USER,
                        ResearchEventType.ApprovalRequested, ResearchEventType.SessionStateChanged);
            case APPROVE_DRAFT:
                return require(current, phase == ResearchPhase.REVIEW && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.FINALIZATION, ResearchRunState.RUNNING,
                        ResearchEventType.SessionStateChanged);
            case REQUEST_REVISION:
                if (phase == ResearchPhase.EVIDENCE && run == ResearchRunState.WAITING_FOR_USER) {
                    return accept(current, ResearchPhase.RESEARCH, ResearchRunState.RUNNING,
                            ResearchEventType.RevisionRequested, ResearchEventType.SessionStateChanged);
                }
                if (phase == ResearchPhase.REVIEW && run == ResearchRunState.WAITING_FOR_USER) {
                    return accept(current, ResearchPhase.DRAFT, ResearchRunState.RUNNING,
                            ResearchEventType.RevisionRequested, ResearchEventType.SessionStateChanged);
                }
                return reject(current, command);
            case REQUEST_FINAL_REVIEW:
                return require(current, phase == ResearchPhase.FINALIZATION && run == ResearchRunState.RUNNING,
                        ResearchPhase.FINALIZATION, ResearchRunState.WAITING_FOR_USER,
                        ResearchEventType.ApprovalRequested, ResearchEventType.SessionStateChanged);
            case APPROVE_FINAL:
                return require(current,
                        phase == ResearchPhase.FINALIZATION && run == ResearchRunState.WAITING_FOR_USER,
                        ResearchPhase.FINALIZATION, ResearchRunState.COMPLETED,
                        ResearchEventType.ResearchCompleted, ResearchEventType.SessionStateChanged);
            case PAUSE:
                return require(current, run == ResearchRunState.RUNNING,
                        phase, ResearchRunState.PAUSED, ResearchEventType.SessionStateChanged);
            case RESUME:
                return require(current, run == ResearchRunState.PAUSED,
                        phase, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            case CANCEL:
                return require(current, !run.isTerminal(),
                        phase, ResearchRunState.CANCELLED, ResearchEventType.SessionStateChanged);
            case BLOCK:
                return require(current,
                        run == ResearchRunState.RUNNING || run == ResearchRunState.WAITING_FOR_USER,
                        phase, ResearchRunState.BLOCKED,
                        ResearchEventType.ResearchBlocked, ResearchEventType.SessionStateChanged);
            case UNBLOCK:
                return require(current, run == ResearchRunState.BLOCKED,
                        phase, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            case FAIL:
                return require(current, !run.isTerminal(),
                        phase, ResearchRunState.FAILED,
                        ResearchEventType.ResearchFailed, ResearchEventType.SessionStateChanged);
            case RETRY:
                return require(current, run == ResearchRunState.FAILED,
                        phase, ResearchRunState.RUNNING, ResearchEventType.SessionStateChanged);
            default:
                return reject(current, command);
        }
    }

    private ResearchTransitionResult require(ResearchSessionState current, boolean allowed,
                                             ResearchPhase newPhase, ResearchRunState newRun,
                                             ResearchEventType... eventTypes) {
        if (!allowed) {
            return ResearchTransitionResult.rejected(current,
                    "Illegal transition from " + current + ".");
        }
        return accept(current, newPhase, newRun, eventTypes);
    }

    private ResearchTransitionResult accept(ResearchSessionState current, ResearchPhase newPhase,
                                            ResearchRunState newRun, ResearchEventType... eventTypes) {
        ResearchSessionState next = current.advance(newPhase, newRun);
        long revision = next.getRevision();
        long timestamp = timeSource.now();
        List<ResearchEvent> events = new ArrayList<ResearchEvent>();
        for (ResearchEventType type : eventTypes) {
            events.add(new ResearchEvent(idGenerator.newId(), sessionId, revision, timestamp, type,
                    next.getPhase() + "/" + next.getRunState()));
        }
        return ResearchTransitionResult.accepted(next, events);
    }

    private static ResearchTransitionResult reject(ResearchSessionState current, ResearchCommand command) {
        return ResearchTransitionResult.rejected(current,
                command.getType() + " is not allowed in state " + current + ".");
    }
}
