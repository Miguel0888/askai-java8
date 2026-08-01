package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the model context for ONE turn from the active phase's {@link PhaseAssistantProfile}: the phase's
 * own system prompt, the per-turn research-context system message, and the running history. This is the single
 * place where "active phase → its assistant context" is realized, and the seam where RA-P6 §10/§11 will later
 * filter to this phase's own chat plus the latest relevant artifacts (per the profile's
 * {@link PhaseContextPolicy}). For now history is passed through unchanged; the policy is carried, not enforced.
 */
public final class PhaseContextAssembler {

    /**
     * system(phase prompt) + system(live research context) + the running user/assistant history. The profile
     * decides the FIRST system message, so a different active phase yields a different assistant context.
     */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      String confirmedQuestion, List<String> confirmedAspects,
                                      String proposedQuestion, List<String> proposedAspects,
                                      List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(profile.getSystemPrompt()));
        messages.add(ChatMessage.system(TeamAgentPlaybook.stateContext(
                state, confirmedQuestion, confirmedAspects, proposedQuestion, proposedAspects)));
        if (history != null) {
            messages.addAll(history);
        }
        return messages;
    }
}
