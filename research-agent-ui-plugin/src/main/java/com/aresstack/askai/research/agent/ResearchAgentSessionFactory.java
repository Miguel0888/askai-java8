package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.research.backend.FakeResearchSessionBackend;
import com.aresstack.askai.research.backend.RealResearchScheduler;
import com.aresstack.askai.research.backend.ResearchClock;
import com.aresstack.askai.research.backend.ResearchIdGenerator;

/**
 * Stateless factory creating a fully isolated {@link ResearchAgentSession} per request, each with its own
 * deterministic {@link FakeResearchSessionBackend} on its own daemon scheduler (closed with the session). The
 * later ACP backend replaces only the backend port here.
 */
public final class ResearchAgentSessionFactory implements AgentSessionFactory {

    /** Delay between simulated run steps in the shipped clickdummy. */
    private static final long STEP_DELAY_MILLIS = 350L;

    @Override
    public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
        RealResearchScheduler scheduler = new RealResearchScheduler();
        FakeResearchSessionBackend backend = new FakeResearchSessionBackend(
                scheduler, ResearchClock.system(), ResearchIdGenerator.random(), STEP_DELAY_MILLIS);
        return new ResearchAgentSession(backend, scheduler, hostContext,
                request.getSessionId(), request.getProjectId());
    }
}
