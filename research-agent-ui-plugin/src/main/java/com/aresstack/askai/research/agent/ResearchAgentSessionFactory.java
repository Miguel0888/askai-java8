package com.aresstack.askai.research.agent;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClientFactory;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.research.acp.ResearchBackendMode;
import com.aresstack.askai.research.backend.FakeResearchSessionBackend;
import com.aresstack.askai.research.backend.RealResearchScheduler;
import com.aresstack.askai.research.backend.ResearchClock;
import com.aresstack.askai.research.backend.ResearchIdGenerator;
import com.aresstack.askai.research.host.ProductiveResearchBackendFactory;
import com.aresstack.askai.research.host.ProductiveResearchSessionResources;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateless factory creating a fully isolated {@link ResearchAgentSession} per request. The backend is chosen
 * EXCLUSIVELY by the persisted, validated {@link ResearchRuntimeSettings} mode:
 * <ul>
 * <li>{@code FAKE} → the deterministic clickdummy backend (development/demo mode, clearly labelled in the
 *     runtime settings view);</li>
 * <li>{@code ACP} → the productive chain via {@link ProductiveResearchBackendFactory} (external agent
 *     process, research-control + browser-bridge endpoints, Playwright sidecar, file-persisted sources).</li>
 * </ul>
 * A productive start failure (invalid configuration, missing host service, sidecar not READY) throws with the
 * concrete reason — there is NO automatic fallback to the fake backend in any direction.
 */
public final class ResearchAgentSessionFactory implements AgentSessionFactory {

    /** Delay between simulated run steps in the shipped clickdummy. */
    private static final long STEP_DELAY_MILLIS = 350L;

    /** One factory instance per plugin load → one productive generation per plugin generation. */
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private final long generationId = GENERATIONS.incrementAndGet();

    @Override
    public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
        ResearchRuntimeSettings settings = ResearchRuntimeSettings.load(hostContext.getStateStore());
        if (settings.getMode() == ResearchBackendMode.ACP) {
            return createProductive(settings, request, hostContext);
        }
        RealResearchScheduler scheduler = new RealResearchScheduler();
        FakeResearchSessionBackend backend = new FakeResearchSessionBackend(
                scheduler, ResearchClock.system(), ResearchIdGenerator.random(), STEP_DELAY_MILLIS);
        return new ResearchAgentSession(backend, scheduler, hostContext,
                request.getSessionId(), request.getProjectId());
    }

    private AgentSession createProductive(ResearchRuntimeSettings settings,
                                          AgentSessionCreationRequest request,
                                          AgentHostContext hostContext) {
        List<String> problems = settings.validateProductive();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The productive research configuration is not usable "
                    + "(no fallback to the fake backend): " + problems);
        }
        McpServerRegistry registry = requireService(hostContext, McpServerRegistry.class);
        McpToolClientFactory toolClients = requireService(hostContext, McpToolClientFactory.class);
        AcpAgentConnector connector = requireService(hostContext, AcpAgentConnector.class);

        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                registry, toolClients, connector, settings.toRuntimeConfig(), generationId);
        final ProductiveResearchSessionResources resources;
        try {
            resources = factory.createSession(request.getSessionId(),
                    hostContext.getPluginPathService().getWorkspaceDirectory(request.getSessionId()));
        } catch (IOException ex) {
            throw new IllegalStateException("The productive research backend could not be started "
                    + "(no fallback to the fake backend): " + ex.getMessage(), ex);
        }
        return new ResearchAgentSession(resources.getBackend(), null, hostContext,
                request.getSessionId(), request.getProjectId(), new Runnable() {
                    public void run() {
                        resources.close(); // endpoints → sidecar client → sidecar process
                    }
                });
    }

    private static <T> T requireService(AgentHostContext hostContext, Class<T> type) {
        T service = hostContext.getService(type);
        if (service == null) {
            throw new IllegalStateException("The host does not provide the required service "
                    + type.getSimpleName() + " for the productive research mode "
                    + "(no fallback to the fake backend).");
        }
        return service;
    }
}
