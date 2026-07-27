package com.aresstack.askai.research.state;

import com.aresstack.askai.research.state.oo.OoTransition;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;
import com.aresstack.askai.research.state.oo.ResearchStateContext;
import com.aresstack.askai.research.state.oo.ResearchStateFactory;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.util.Collections;
import java.util.UUID;

/**
 * The canonical research lifecycle, now a thin facade over the hierarchical OO state model in
 * {@link com.aresstack.askai.research.state.oo}. It keeps the stable {@link ResearchStateMachine} port and the
 * legacy {@link ResearchSessionState} (phase + runState + revision) representation: each dispatch reconstructs
 * the OO phase/state from the incoming pair, delegates to it, then maps the result back and advances the
 * revision. No central switch remains here — every transition rule lives in the phase/state objects.
 *
 * <p>The legacy pair cannot represent an interruption's precise continuation, so a reconstructed
 * paused/blocked/failed state continues into the phase's working state (see
 * {@link ResearchStateFactory#defaultContinuationStateId}). The native OO model preserves the exact
 * continuation and is what the memento/visualization use.</p>
 */
public final class DefaultResearchStateMachine implements ResearchStateMachine {

    private final String sessionId;
    private final IdGenerator idGenerator;
    private final TimeSource timeSource;
    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

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
        ResearchPhaseState phase = toPhaseState(current);
        ResearchStateContext context = new ResearchStateContext(sessionId, factory,
                new ResearchStateContext.IdGenerator() {
                    public String newId() {
                        return idGenerator.newId();
                    }
                });
        OoTransition transition = phase.handle(context, command);
        if (!transition.isAccepted()) {
            return ResearchTransitionResult.rejected(current, transition.getReason());
        }
        ResearchPhaseState nextPhase = transition.getNext();
        ResearchPhase newPhase = ResearchStateIds.phase(nextPhase.getPhaseId());
        ResearchRunState newRun = ResearchStateIds.runState(nextPhase.getCurrentState().getStateId());
        ResearchSessionState next = current.advance(newPhase, newRun);
        ResearchEvent event = new ResearchEvent(idGenerator.newId(), sessionId, next.getRevision(),
                timeSource.now(), ResearchEventType.SessionStateChanged,
                next.getPhase() + "/" + next.getRunState());
        return ResearchTransitionResult.accepted(next, Collections.singletonList(event));
    }

    private ResearchPhaseState toPhaseState(ResearchSessionState current) {
        String phaseId = ResearchStateIds.phaseId(current.getPhase());
        String stateId = ResearchStateIds.stateId(phaseId, current.getRunState());
        String continuation = null;
        if (ResearchStateIds.PAUSED.equals(stateId)
                || ResearchStateIds.BLOCKED.equals(stateId)
                || ResearchStateIds.FAILED.equals(stateId)) {
            continuation = factory.defaultContinuationStateId(phaseId);
        }
        return factory.phase(phaseId, factory.state(phaseId, stateId, continuation, null));
    }
}
