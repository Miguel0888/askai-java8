package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;

/**
 * Derives the USER-facing decision actions a restored session re-offers, from the live phase state. The
 * restored transcript brings back the conversation TEXT; interactive buttons are never persisted — they are
 * re-derived through this seam so they always match the state (single source of truth:
 * {@link com.aresstack.askai.research.state.oo.PhaseState#getAllowedCommands()}). The event-bus card model
 * (issue #13) will provide an alternative implementation without touching the session.
 */
public interface RestoredActionsProvider {

    /** The decision actions for the state, in display order; empty → no card is shown. */
    java.util.List<RestoredAction> deriveFrom(ResearchPhaseState phase);

    /** One user decision: the state-machine command plus the stable action id its label/handling keys on. */
    final class RestoredAction {
        private final ResearchCommandType command;
        private final String actionId;

        public RestoredAction(ResearchCommandType command, String actionId) {
            this.command = command;
            this.actionId = actionId;
        }

        public ResearchCommandType getCommand() {
            return command;
        }

        public String getActionId() {
            return actionId;
        }
    }
}
