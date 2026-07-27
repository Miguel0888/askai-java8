package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Set;

/**
 * The native, memento-based research state machine: the single source of functional truth for the session
 * lifecycle. It operates directly on a {@link ResearchStateMemento} — the exact live state, including the precise
 * continuation of an interruption and the pending approval id — so no continuation is ever guessed. It never
 * throws for a normal illegal transition; those are returned as rejections. The live backend uses this port; the
 * legacy phase/run-state {@code ResearchStateMachine} remains only as a deprecated compatibility adapter.
 */
public interface ResearchStateMachinePort {

    ResearchStateTransitionResult dispatch(ResearchStateMemento current, ResearchCommand command);

    /**
     * @return the commands allowed in the given memento's current state. This is a <em>pure</em> query — it never
     *     runs a probe transition and never consumes an id — so it is the correct source for enablement checks.
     */
    Set<ResearchCommandType> allowedCommands(ResearchStateMemento current);
}
