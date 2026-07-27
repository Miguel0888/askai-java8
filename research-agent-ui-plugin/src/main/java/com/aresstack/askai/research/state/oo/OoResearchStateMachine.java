package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchEvent;
import com.aresstack.askai.research.state.ResearchEventType;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * The native memento-based state machine. Each dispatch restores the hierarchical OO phase/state from the
 * incoming {@link ResearchStateMemento} (rejecting an invalid memento), delegates the command to that state,
 * and — on acceptance — snapshots the resulting state back to a new memento with {@code revision + 1}. Because
 * the memento carries the precise continuation and pending approval id, an interruption's exact resume point
 * (including the original approval id) is preserved rather than defaulted.
 */
public final class OoResearchStateMachine implements ResearchStateMachinePort {

    /** Supplies stable ids for freshly-raised approval gates and emitted events (injectable for tests). */
    public interface IdGenerator {
        String newId();
    }

    /** Supplies event timestamps (injectable for deterministic tests). */
    public interface TimeSource {
        long now();
    }

    private final String sessionId;
    private final IdGenerator idGenerator;
    private final TimeSource timeSource;
    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

    public OoResearchStateMachine(String sessionId) {
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

    public OoResearchStateMachine(String sessionId, IdGenerator idGenerator, TimeSource timeSource) {
        this.sessionId = sessionId;
        this.idGenerator = idGenerator;
        this.timeSource = timeSource;
    }

    /** @return the initial memento of a new session: SCOPING / new at revision 0. */
    public ResearchStateMemento initialMemento() {
        return factory.snapshot(factory.initialPhase(), 0L);
    }

    @Override
    public Set<ResearchCommandType> allowedCommands(ResearchStateMemento current) {
        return factory.restore(current).getCurrentState().getAllowedCommands();
    }

    @Override
    public ResearchStateTransitionResult dispatch(ResearchStateMemento current, ResearchCommand command) {
        ResearchPhaseState phase;
        try {
            phase = factory.restore(current);
        } catch (IllegalArgumentException ex) {
            return ResearchStateTransitionResult.rejected(current, "invalid memento: " + ex.getMessage());
        }
        ResearchStateContext context = new ResearchStateContext(sessionId, factory,
                new ResearchStateContext.IdGenerator() {
                    public String newId() {
                        return idGenerator.newId();
                    }
                });
        OoTransition transition = phase.handle(context, command);
        if (!transition.isAccepted()) {
            return ResearchStateTransitionResult.rejected(current, transition.getReason());
        }
        ResearchStateMemento next = factory.snapshot(transition.getNext(), current.getRevision() + 1);
        ResearchEvent event = new ResearchEvent(idGenerator.newId(), sessionId, next.getRevision(),
                timeSource.now(), ResearchEventType.SessionStateChanged,
                next.getPhaseId() + "/" + next.getStateId());
        return ResearchStateTransitionResult.accepted(next, Collections.singletonList(event));
    }
}
