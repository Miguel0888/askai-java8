package com.aresstack.askai.research.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpEndpointDescriptor;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolClientFactory;
import com.aresstack.askai.research.acp.AcpResearchSessionBackend;
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
    /** Stored browser-search overrides (canonical codec keys) + their revision, from the host store. */
    private final Map<String, String> browserSearchValues;
    private final long browserSearchRevision;

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId) {
        this(registry, toolClients, connector, config, generationId,
                Collections.<String, String>emptyMap(), 0L);
    }

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId, Map<String, String> browserSearchValues,
                                            long browserSearchRevision) {
        this.registry = registry;
        this.toolClients = toolClients;
        this.connector = connector;
        this.config = config;
        this.generationId = generationId;
        this.browserSearchValues = browserSearchValues;
        this.browserSearchRevision = browserSearchRevision;
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

        // A2c snapshot semantics: global settings are only the TEMPLATE for NEW sessions. A stored
        // profile (session resume) is reused EXACTLY — never silently replaced by current globals;
        // an unreadable/corrupt profile is a clear recovery error. A fresh session freezes the
        // validated globals into one immutable snapshot persisted with the session.
        File searchConfigDir = new File(projectDir, "browser-search");
        if (!searchConfigDir.isDirectory() && !searchConfigDir.mkdirs()) {
            throw new IOException("Cannot create " + searchConfigDir);
        }
        File profileFile = new File(searchConfigDir, "search-profile.json");
        File sidecarConfigFile = new File(searchConfigDir, "browser-config.json");
        com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot profile;
        if (profileFile.isFile()) {
            try {
                profile = com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot
                        .parse(readUtf8(profileFile));
            } catch (IllegalArgumentException ex) {
                throw new IOException("Stored search profile of this session is unusable ("
                        + profileFile + "): " + ex.getMessage());
            }
        } else {
            com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.Decoded decoded =
                    com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec
                            .fromValues(browserSearchValues);
            if (!decoded.violations.isEmpty()) {
                throw new IOException("Legacy browser search settings are invalid:\n"
                        + new com.aresstack.askai.browser.search.SettingsValidationResult(
                                decoded.violations).describe());
            }
            com.aresstack.askai.browser.search.SettingsValidationResult validation =
                    new com.aresstack.askai.browser.search
                            .DefaultLegacyBrowserSearchSettingsValidator().validate(decoded.settings);
            if (!validation.isValid()) {
                throw new IOException("Legacy browser search settings failed validation:\n"
                        + validation.describe());
            }
            profile = com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot.create(
                    sessionKey, browserSearchRevision, System.currentTimeMillis(), decoded.settings);
            writeUtf8(profileFile, profile.toJson());
        }
        // The browser-near SUBSET for the sidecar is regenerated from the SNAPSHOT on every start
        // (AI/prompt/reranker settings never enter the browser process).
        writeUtf8(sidecarConfigFile, new com.aresstack.askai.browser.search
                .LegacyBrowserSearchConfigDocument(
                        profile.schemaVersion, profile.profileRevision, profile.settingsDigest,
                        com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument
                                .sidecarSubset(com.aresstack.askai.browser.search
                                        .LegacyBrowserSearchSettingsCodec
                                        .toValues(profile.settings))).toJson());
        File fullConfigFile = profileFile;

        // 1. THE persistent project context (Commit 1 of the guided artifact flow): exactly one
        // file-backed artifact store, source repository, state store and metadata store per
        // project directory — the productive path never constructs an in-memory artifact store.
        com.aresstack.askai.research.store.ResearchProjectContext projectContext =
                com.aresstack.askai.research.store.ResearchProjectContext.open(sessionKey, projectDir);
        CaptureStore captures = new CaptureStore(200);
        final FileResearchSourceRepository repository = projectContext.getFileSourceRepository();
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
                }, index, highestSourceNumber(repository));
        OoResearchStateMachine stateMachine = new OoResearchStateMachine(sessionKey);

        BrowserMcpSidecarProcess sidecar = null;
        McpToolClient sidecarClient = null;
        BrowserBridgeEndpoint bridge = null;
        com.aresstack.askai.research.mcp.ResearchControlEndpoint control = null;
        try {
            // 2. Browser sidecar process (fails with its specific readiness status when not READY).
            sidecar = BrowserMcpSidecarProcess.start(config, SIDECAR_READY_TIMEOUT_SECONDS,
                    sidecarConfigFile.getAbsolutePath());
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
            // The agent reads the FULL settings document (AI/prompt settings stay host-side only in
            // the sense of never reaching the BROWSER process; the agent needs them).
            baseEnv.put("ASKAI_BROWSER_SEARCH_CONFIG", fullConfigFile.getAbsolutePath());
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
                    repository, acceptance, projectContext, control, bridge,
                    sidecarClient, sidecar, backend);
            resources.setSearchProfile(profile);
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

    /** The highest persisted {@code source-N} number of this project (0 for a fresh project). */
    private static long highestSourceNumber(FileResearchSourceRepository repository) {
        long highest = 0;
        for (com.aresstack.askai.research.sources.ResearchSourceRecord record
                : repository.find(com.aresstack.askai.research.sources.SourceQuery.all())) {
            String id = record.getSourceId();
            if (id != null && id.startsWith("source-")) {
                try {
                    highest = Math.max(highest, Long.parseLong(id.substring("source-".length())));
                } catch (NumberFormatException ignored) {
                    // foreign id scheme — never counted, never reissued either
                }
            }
        }
        return highest;
    }

    /** Used by generation preparation: validation without side effects. */
    public List<String> validateConfig() {
        return Collections.unmodifiableList(config.validate());
    }

    private static void writeUtf8(File file, String content) throws IOException {
        java.io.OutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String readUtf8(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    }
}
