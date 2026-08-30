package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution;

/**
 * Contributes the phase-bound surface (Phase 1: the out-of-scope sky over the transcript's top)
 * for research sessions. Non-research sessions get nothing.
 */
public final class ResearchPhaseAccessoryContribution implements ComposerAccessoryContribution {

    public String getId() {
        return "research.phase.accessory";
    }

    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    public ComposerAccessory create(ComposerAccessoryContext context) {
        return new ResearchPhaseAccessory((ResearchAgentSession) context.getSession(),
                context.getUiExecutor());
    }
}
