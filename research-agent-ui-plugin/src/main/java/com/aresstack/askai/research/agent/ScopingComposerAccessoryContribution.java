package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution;

/**
 * Contributes the scoping controls (exploration map + search suggestions + query draft) as a composer accessory
 * for research sessions, so they are immediately visible above the composer during scoping — no artifact tab to
 * open. Non-research sessions get nothing.
 */
public final class ScopingComposerAccessoryContribution implements ComposerAccessoryContribution {

    public String getId() {
        return "research.scoping.accessory";
    }

    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    public ComposerAccessory create(ComposerAccessoryContext context) {
        return new ScopingComposerAccessory((ResearchAgentSession) context.getSession(),
                context.getUiExecutor());
    }
}
