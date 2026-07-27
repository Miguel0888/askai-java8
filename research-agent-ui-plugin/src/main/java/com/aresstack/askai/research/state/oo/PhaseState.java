package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Set;

/**
 * A phase's inner run state (running, a waiting/approval gate, an interruption, or a terminal). It owns the
 * commands it accepts and the transition each produces. There is no central switch: each state handles only
 * the commands it allows and rejects the rest. {@link #getAllowedCommands()} is the single source of truth for
 * slash-command gating, UI actions and the state visualization.
 */
public interface PhaseState {

    String getStateId();

    Set<ResearchCommandType> getAllowedCommands();

    boolean isTerminal();

    boolean requiresApproval();

    /** @return the pending approval id for an approval gate, else {@code null}. */
    String getPendingApprovalId();

    /** @return the id of the state to continue into for an interruption (paused/blocked/failed), else {@code null}. */
    String getContinuationStateId();

    OoTransition handle(ResearchStateContext context, ResearchPhaseState phase, ResearchCommand command);
}
