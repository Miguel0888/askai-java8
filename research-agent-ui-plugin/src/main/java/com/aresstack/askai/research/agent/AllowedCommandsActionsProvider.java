package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Default {@link RestoredActionsProvider}: filters {@code getAllowedCommands()} down to the commands that
 * represent a USER decision and maps each to its stable action id (the {@code actionLabel} key). Agent-driven
 * forward commands (propose/submit/review requests) and interrupt machinery (pause/block/fail/cancel) never
 * become restored buttons — they are reachable through the composer and slash commands instead.
 */
public final class AllowedCommandsActionsProvider implements RestoredActionsProvider {

    @Override
    public List<RestoredAction> deriveFrom(ResearchPhaseState phase) {
        List<RestoredAction> actions = new ArrayList<RestoredAction>();
        Set<String> seenActionIds = new LinkedHashSet<String>();
        for (ResearchCommandType command : phase.getCurrentState().getAllowedCommands()) {
            String actionId = decisionActionId(command);
            if (actionId != null && seenActionIds.add(actionId)) {
                actions.add(new RestoredAction(command, actionId));
            }
        }
        return actions;
    }

    /** The action id for a user decision command, or {@code null} for commands that are not user buttons. */
    private static String decisionActionId(ResearchCommandType command) {
        switch (command) {
            case APPROVE_OUTLINE:
            case APPROVE_EVIDENCE:
            case APPROVE_DRAFT:
            case APPROVE_FINAL:
                return "approve";
            case REQUEST_OUTLINE_CHANGES:
            case REQUEST_REVISION:
                return "changes";
            case RESUME:
            case UNBLOCK:
                return "resume";
            case RETRY:
                return "retry";
            case START_RESEARCH:
            case START_DRAFTING:
                return "continue";
            default:
                return null;
        }
    }
}
