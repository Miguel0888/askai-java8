package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Set;

/**
 * A research phase together with its current inner {@link PhaseState}. The phase constrains which inner states
 * are reachable; impossible (phase, state) combinations are simply never constructed by the factory. Handling a
 * command delegates to the current inner state, which knows the phase's allowed transitions.
 */
public interface ResearchPhaseState {

    String getPhaseId();

    PhaseState getCurrentState();

    /** Convenience: the currently allowed commands (from the inner state). */
    Set<ResearchCommandType> getAllowedCommands();

    OoTransition handle(ResearchStateContext context, ResearchCommand command);

    /** @return a phase state in the same phase but with a different inner state (used for interruptions). */
    ResearchPhaseState withState(PhaseState newState);
}
