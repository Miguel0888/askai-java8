package com.aresstack.askai.research.state;

/**
 * Pure research state machine: the single source of functional truth for the session lifecycle. It never
 * throws for a normal illegal transition — those are returned as rejections. UI and any backend follow this,
 * not the other way around.
 */
public interface ResearchStateMachine {

    ResearchTransitionResult dispatch(ResearchSessionState current, ResearchCommand command);

    /** Supplies stable ids for emitted events (injectable for deterministic tests). */
    interface IdGenerator {
        String newId();
    }

    /** Supplies event timestamps (injectable for deterministic tests). */
    interface TimeSource {
        long now();
    }
}
