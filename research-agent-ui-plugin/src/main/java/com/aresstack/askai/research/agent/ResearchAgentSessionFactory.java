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
        // The agent speaks the persisted language (English default, German translation) from the start.
        ResearchPlaybook.setLanguage(
                ResearchRuntimeSettings.loadLanguage(hostContext.getStateStore()));
        ResearchRuntimeSettings settings = ResearchRuntimeSettings.load(hostContext.getStateStore());
        // There is NO user-facing mode choice: productive is simply THE mode whenever its requirements
        // are met (auto-completed defaults). A persisted FAKE value remains a developer-only override.
        if (settings.getMode() == ResearchBackendMode.FAKE
                && hostContext.getStateStore() != null
                && ResearchRuntimeSettings.hasPersistedMode(hostContext.getStateStore())) {
            return createDemo(request, hostContext, null);
        }
        ResearchRuntimeSettings completed =
                com.aresstack.askai.research.host.ResearchRuntimeDefaults.complete(settings);
        List<String> problems = completed.validateProductive();
        if (!problems.isEmpty()) {
            // Requirements not met → the session starts in DEMO mode with a VISIBLE notice listing
            // exactly what is missing. This is not a silent fallback; a start FAILURE with met
            // requirements still fails visibly below.
            StringBuilder notice = new StringBuilder(
                    "Research runs in DEMO mode — the productive runtime is not available:\n");
            for (String problem : problems) {
                notice.append("  - ").append(problem).append('\n');
            }
            notice.append("Fix this in the Runtime tab, then open a new Research session.");
            return createDemo(request, hostContext, notice.toString());
        }
        return createProductive(completed, request, hostContext);
    }

    private AgentSession createDemo(AgentSessionCreationRequest request, AgentHostContext hostContext,
                                    String visibleNotice) {
        RealResearchScheduler scheduler = new RealResearchScheduler();
        FakeResearchSessionBackend backend = new FakeResearchSessionBackend(
                scheduler, ResearchClock.system(), ResearchIdGenerator.random(), STEP_DELAY_MILLIS);
        ResearchAgentSession session = new ResearchAgentSession(backend, scheduler, hostContext,
                request.getSessionId(), request.getProjectId());
        if (visibleNotice != null) {
            session.setStartupNotice(visibleNotice);
        }
        configureNarration(session, hostContext);
        return session;
    }

    /**
     * LLM narration is strictly opt-in (settings toggle) AND host-dependent (inference port present).
     * Both absent/off → the session keeps the static narrator, visibly identical.
     */
    private static void configureNarration(ResearchAgentSession session, AgentHostContext hostContext) {
        if (!com.aresstack.askai.research.host.ResearchRuntimeSettings
                .loadLlmNarration(hostContext.getStateStore())) {
            return;
        }
        com.aresstack.askai.agent.model.inference.AgentInferencePort port = hostContext.getService(
                com.aresstack.askai.agent.model.inference.AgentInferencePort.class);
        if (port != null) {
            session.configureNarration(
                    new com.aresstack.askai.research.agent.narration.LlmNarrator(port));
        }
    }

    private AgentSession createProductive(ResearchRuntimeSettings settings,
                                          AgentSessionCreationRequest request,
                                          AgentHostContext hostContext) {
        // Empty fields are completed from the detectable environment (running JVM, assembled
        // distribution, discovered Java 21) — explicit user values always win.
        settings = com.aresstack.askai.research.host.ResearchRuntimeDefaults.complete(settings);
        List<String> problems = settings.validateProductive();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The productive research configuration is not usable "
                    + "(no fallback to the fake backend): " + problems);
        }
        McpServerRegistry registry = requireService(hostContext, McpServerRegistry.class);
        McpToolClientFactory toolClients = requireService(hostContext, McpToolClientFactory.class);
        AcpAgentConnector connector = requireService(hostContext, AcpAgentConnector.class);
        // The reranker snapshot provider is MANDATORY for the productive browser path — a missing host
        // service fails the session start visibly (no fallback to a reranker-less run).
        com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider rerankerSnapshots =
                requireService(hostContext,
                        com.aresstack.askai.agent.model.reranker
                                .RerankerConfigurationSnapshotProvider.class);
        // The reranker model is chosen centrally in AskAI (Configuration → AI models); the host snapshot
        // provider resolves it. Any legacy plugin-side selection still flows through settings.toRuntimeConfig()
        // as a transitional fallback and is migrated into the central store on first use.
        // OPTIONAL structured-inference provider (the central main model for SERP layout repair): absent →
        // the agent keeps the honest unavailable-fallback, so it is looked up leniently (never required).
        com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider inferenceSnapshots =
                hostContext.getService(
                        com.aresstack.askai.agent.model.inference
                                .InferenceConfigurationSnapshotProvider.class);

        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                registry, toolClients, connector, settings.toRuntimeConfig(), generationId,
                com.aresstack.askai.research.host.LegacyBrowserSearchSettingsStore
                        .loadValues(hostContext.getStateStore()),
                com.aresstack.askai.research.host.LegacyBrowserSearchSettingsStore
                        .revision(hostContext.getStateStore()),
                rerankerSnapshots, inferenceSnapshots);
        java.io.File sessionDirectory =
                hostContext.getPluginPathService().getWorkspaceDirectory(request.getSessionId());
        final ProductiveResearchSessionResources resources;
        try {
            resources = factory.createSession(request.getSessionId(), sessionDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("The productive research backend could not be started "
                    + "(no fallback to the fake backend): " + ex.getMessage(), ex);
        }
        // Register the now-running session so AskAI can re-publish its descriptors on a central model change
        // (unregistered in ResearchAgentSession.close()). Registration happens ONLY after a successful start.
        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry activeSessions =
                hostContext.getService(
                        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry.class);
        if (activeSessions != null) {
            activeSessions.register(request.getSessionId(), sessionDirectory);
        }
        // The session OWNS the resources: structured commands route to their state machine, close() tears
        // them down last (endpoints → sidecar client → sidecar process).
        ResearchAgentSession session = new ResearchAgentSession(resources.getBackend(), null, hostContext,
                request.getSessionId(), request.getProjectId(), resources);
        configureNarration(session, hostContext);
        return session;
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
