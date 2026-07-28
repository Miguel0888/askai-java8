package com.aresstack.askai.research.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpEndpointDescriptor;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolClientFactory;
import com.aresstack.askai.research.acp.AcpResearchSessionBackend;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.ResearchSearchIndex;
import com.aresstack.askai.research.capture.SourceAcceptanceService;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.store.FileResearchSourceRepository;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PRODUCTIVE factory replacing {@code FakeResearchSessionBackend} for real research sessions. It composes
 * exclusively existing, proven components — no research or browser logic of its own, no
 * {@code ResearchLoop}/{@code SolonToolInvoker}/{@code BrowserSession}/Playwright reference anywhere:
 * <ol>
 * <li>Commit-37 lifecycle: {@link CaptureStore} → {@link SourceAcceptanceService} →
 *     {@link FileResearchSourceRepository} (persistent) + the in-memory search index (the only existing
 *     index adapter; the Lucene adapter stays open as MCP-P006),</li>
 * <li>the research-control endpoint (state-gated tools, Commit 33) bound to the session's own hierarchical
 *     state machine — the single transition authority,</li>
 * <li>one browser sidecar process per session + the {@link BrowserBridgeEndpoint} carrying the
 *     {@code capture_id} convention,</li>
 * <li>the {@link AcpResearchSessionBackend} handing both endpoint descriptors to the external agent via
 *     structured launch environment.</li>
 * </ol>
 * Construction is atomic: any failure rolls back everything already started and nothing half-built is
 * returned. All external paths come from the explicit {@link ResearchRuntimeConfig} — no hidden globals.
 */
public final class ProductiveResearchBackendFactory {

    private static final long SIDECAR_READY_TIMEOUT_SECONDS = 120;

    private final McpServerRegistry registry;
    private final McpToolClientFactory toolClients;
    private final AcpAgentConnector connector;
    private final ResearchRuntimeConfig config;
    private final long generationId;

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId) {
        this.registry = registry;
        this.toolClients = toolClients;
        this.connector = connector;
        this.config = config;
        this.generationId = generationId;
    }

    /**
     * Build all resources for one research session. @param projectDir the session's project directory
     * (sources are persisted under {@code <projectDir>/sources}).
     */
    public ProductiveResearchSessionResources createSession(String sessionKey, File projectDir)
            throws IOException {
        List<String> problems = config.validate();
        if (!problems.isEmpty()) {
            throw new IOException("Research runtime configuration is not usable: " + problems);
        }

        // 1. Session-owned stores + state machine (pure, nothing to roll back).
        CaptureStore captures = new CaptureStore(200);
        File sourcesDir = new File(projectDir, "sources");
        final FileResearchSourceRepository repository = new FileResearchSourceRepository(sourcesDir);
        ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
        SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repository,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        try {
                            repository.put(record);
                        } catch (IOException ex) {
                            throw new IllegalStateException(
                                    "Cannot persist source " + record.getSourceId(), ex);
                        }
                    }
                }, index);
        OoResearchStateMachine stateMachine = new OoResearchStateMachine(sessionKey);

        BrowserMcpSidecarProcess sidecar = null;
        McpToolClient sidecarClient = null;
        BrowserBridgeEndpoint bridge = null;
        com.aresstack.askai.research.mcp.ResearchControlEndpoint control = null;
        try {
            // 2. Browser sidecar process (fails with its specific readiness status when not READY).
            sidecar = BrowserMcpSidecarProcess.start(config, SIDECAR_READY_TIMEOUT_SECONDS);
            sidecarClient = toolClients.connect(sidecar.getMcpUrl(), "streamable");

            // 3. Host endpoints for this session+generation.
            bridge = new BrowserBridgeEndpoint(registry, sidecarClient, captures, sessionKey, generationId);
            bridge.open();

            // The control endpoint needs the live-state context; resources are created first with a
            // placeholder-free two-step wiring: build resources LAST, context reads their state.
            ProductiveResearchSessionResources[] holder = new ProductiveResearchSessionResources[1];
            final com.aresstack.askai.research.state.oo.ResearchStateMemento initial =
                    stateMachine.initialMemento();
            control = new com.aresstack.askai.research.mcp.ResearchControlEndpoint(
                    registry, sessionKey, generationId,
                    new com.aresstack.askai.research.mcp.ResearchControlContext() {
                        // Before the resources object exists (endpoint open() during construction), the
                        // machine is by definition in its initial state; afterwards the live state rules.
                        public String currentPhaseId() {
                            return holder[0] == null ? initial.getPhaseId()
                                    : holder[0].controlContext().currentPhaseId();
                        }

                        public String currentStateId() {
                            return holder[0] == null ? initial.getStateId()
                                    : holder[0].controlContext().currentStateId();
                        }

                        public String statusLine() {
                            return holder[0] == null
                                    ? initial.getPhaseId() + "/" + initial.getStateId() + " rev="
                                            + initial.getRevision()
                                    : holder[0].controlContext().statusLine();
                        }

                        public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore artifactStore() {
                            return holder[0].controlContext().artifactStore();
                        }

                        public com.aresstack.askai.research.sources.ResearchSourceRepository sourceRepository() {
                            return holder[0].controlContext().sourceRepository();
                        }

                        public String acceptCapture(String captureId) {
                            return holder[0].controlContext().acceptCapture(captureId);
                        }
                    });

            // 4. Backend with BOTH endpoint descriptors (structured env hand-off; tokens never logged).
            String agentJava = config.getAgentJavaExecutable();
            Map<String, String> baseEnv = new LinkedHashMap<String, String>();
            AgentLaunchSpec spec = new AgentLaunchSpec(agentJava,
                    java.util.Arrays.asList("-jar", config.getAgentJar()), baseEnv);
            AcpResearchSessionBackend backend = null; // assigned after control.open()

            ProductiveResearchSessionResources resources;
            control.open();
            String researchUrl = registry.endpointUrl(control.getHandle());
            AcpEndpointDescriptor researchDescriptor = new AcpEndpointDescriptor(
                    control.getEndpointId(), researchUrl, "streamable", control.getHandle().getToken());
            String bridgeUrl = registry.endpointUrl(bridge.getHandle());
            AcpEndpointDescriptor browserDescriptor = new AcpEndpointDescriptor(
                    bridge.getEndpointId(), bridgeUrl, "streamable", bridge.getHandle().getToken());
            backend = new AcpResearchSessionBackend(connector, spec, researchDescriptor, browserDescriptor);

            resources = new ProductiveResearchSessionResources(sessionKey, stateMachine, captures,
                    repository, acceptance, new ResearchArtifactStore(), control, bridge,
                    sidecarClient, sidecar, backend);
            holder[0] = resources;
            control.refreshTools(); // now that the live context resolves, publish the initial tool set
            return resources;
        } catch (IOException ex) {
            rollback(control, bridge, sidecarClient, sidecar);
            throw ex;
        } catch (RuntimeException ex) {
            rollback(control, bridge, sidecarClient, sidecar);
            throw ex;
        }
    }

    private static void rollback(com.aresstack.askai.research.mcp.ResearchControlEndpoint control,
                                 BrowserBridgeEndpoint bridge, McpToolClient sidecarClient,
                                 BrowserMcpSidecarProcess sidecar) {
        if (control != null) {
            control.close();
        }
        if (bridge != null) {
            bridge.close();
        }
        if (sidecarClient != null) {
            sidecarClient.close();
        }
        if (sidecar != null) {
            sidecar.close();
        }
    }

    /** Used by generation preparation: validation without side effects. */
    public List<String> validateConfig() {
        return Collections.unmodifiableList(config.validate());
    }
}
