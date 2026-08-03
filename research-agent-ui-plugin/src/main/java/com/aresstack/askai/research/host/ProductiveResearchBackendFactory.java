package com.aresstack.askai.research.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpEndpointDescriptor;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
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
    /** Publishes the MANDATORY per-session reranker snapshot; required for every productive session. */
    private final RerankerConfigurationSnapshotProvider rerankerSnapshots;
    /** OPTIONAL: publishes the per-session structured-inference descriptor (central main model), or null. */
    private final com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider
            inferenceSnapshots;
    /** The initial-search strategy selection; legacy browser publishes NO snapshot (today's behavior). */
    private final SearchStrategySelection searchStrategy;

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId,
                                            RerankerConfigurationSnapshotProvider rerankerSnapshots) {
        this(registry, toolClients, connector, config, generationId,
                Collections.<String, String>emptyMap(), 0L, rerankerSnapshots);
    }

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId, Map<String, String> browserSearchValues,
                                            long browserSearchRevision,
                                            RerankerConfigurationSnapshotProvider rerankerSnapshots) {
        this(registry, toolClients, connector, config, generationId, browserSearchValues,
                browserSearchRevision, rerankerSnapshots, null);
    }

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId, Map<String, String> browserSearchValues,
                                            long browserSearchRevision,
                                            RerankerConfigurationSnapshotProvider rerankerSnapshots,
                                            com.aresstack.askai.agent.model.inference
                                                    .InferenceConfigurationSnapshotProvider inferenceSnapshots) {
        this(registry, toolClients, connector, config, generationId, browserSearchValues,
                browserSearchRevision, rerankerSnapshots, inferenceSnapshots,
                SearchStrategySelection.legacyBrowser());
    }

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId, Map<String, String> browserSearchValues,
                                            long browserSearchRevision,
                                            RerankerConfigurationSnapshotProvider rerankerSnapshots,
                                            com.aresstack.askai.agent.model.inference
                                                    .InferenceConfigurationSnapshotProvider inferenceSnapshots,
                                            SearchStrategySelection searchStrategy) {
        this.registry = registry;
        this.toolClients = toolClients;
        this.connector = connector;
        this.config = config;
        this.generationId = generationId;
        this.browserSearchValues = browserSearchValues;
        this.browserSearchRevision = browserSearchRevision;
        this.rerankerSnapshots = rerankerSnapshots;
        this.inferenceSnapshots = inferenceSnapshots;
        this.searchStrategy = searchStrategy == null
                ? SearchStrategySelection.legacyBrowser() : searchStrategy;
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

        // MANDATORY reranker: publish the per-session snapshot BEFORE any process is started. A missing
        // provider or an unpreparable snapshot fails the session here — there is no browser research run
        // without a reranker, and no silent raw-order fallback.
        if (rerankerSnapshots == null) {
            throw new IOException("The mandatory reranker snapshot provider is not available; "
                    + "a productive research session cannot start without a local reranker.");
        }
        RerankerConfigurationSnapshot rerankerSnapshot;
        try {
            rerankerSnapshot = rerankerSnapshots.prepareForSession(sessionKey, projectDir,
                    config.getSelectedRerankerModel());
        } catch (RerankerConfigurationException ex) {
            throw new IOException("The mandatory reranker could not be prepared for this session: "
                    + ex.getMessage(), ex);
        }

        // OPTIONAL structured-inference descriptor (the central main model for SERP layout repair). Unlike
        // the reranker this NEVER fails the session: an absent provider or an unresolvable main model just
        // means the agent keeps the honest unavailable-fallback (a low-confidence SERP stays unresolvable).
        String inferenceSnapshotPath = "";
        // When the main model cannot be prepared, keep the honest reason (e.g. "No main model is selected…")
        // and hand it to the agent so the TeamAgent's MODEL_UNAVAILABLE bubble tells the user WHAT to fix,
        // instead of a bare "no descriptor configured".
        String inferenceUnavailableReason = "";
        if (inferenceSnapshots != null) {
            try {
                inferenceSnapshotPath = inferenceSnapshots.prepareForSession(sessionKey, projectDir)
                        .getAbsolutePath();
            } catch (com.aresstack.askai.agent.model.inference.InferenceConfigurationException ex) {
                inferenceSnapshotPath = "";
                inferenceUnavailableReason = ex.getMessage() == null ? "" : ex.getMessage();
            }
        } else {
            inferenceUnavailableReason = "This host has no main-model provider "
                    + "(no central chat model is configured).";
        }

        // OPTIONAL initial-search strategy snapshot: published ONLY for an API-provider selection. The
        // legacy browser selection hands over nothing — the absent env var IS the agent's documented
        // legacy path, so existing sessions keep today's behavior exactly. The snapshot carries the
        // SELECTION only; provider credentials stay in ${user.home}/agents/research/providers/.
        String searchStrategySnapshotPath = "";
        if (searchStrategy.isApiProvider()) {
            File strategyFile = new File(projectDir, "search-strategy.json");
            writeUtf8(strategyFile, searchStrategy.toSnapshotJson());
            searchStrategySnapshotPath = strategyFile.getAbsolutePath();
        }

        // 1. THE persistent project context (Commit 1 of the guided artifact flow): exactly one
        // file-backed artifact store, source repository, state store and metadata store per
        // project directory — the productive path never constructs an in-memory artifact store. The
        // mandatory reranker snapshot above is prepared first so a session still fails closed without it.
        com.aresstack.askai.research.store.ResearchProjectContext projectContext =
                com.aresstack.askai.research.store.ResearchProjectContext.open(sessionKey, projectDir);
        // FAIL-CLOSED start gate: only MISSING (new project) or LOADED metadata may start. A
        // corrupt/foreign/unsupported project.properties blocks the session with a repair hint -
        // it never silently restarts as an empty research assignment.
        com.aresstack.askai.research.store.MetadataLoadResult metadataResult =
                projectContext.getMetadataStore().load(sessionKey);
        if (!metadataResult.isUsableForStart()) {
            throw new IOException("The stored research project metadata is unusable ("
                    + metadataResult.getStatus() + "): " + metadataResult.getReason()
                    + " - repair or remove " + new File(projectDir, "project.properties"));
        }
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
        // Knowledge pipeline (§3): a persistent, project-scoped processing queue and the acceptance hook. The
        // enqueue is a reaction to source acceptance that is INDEPENDENT of the source-level Lucene index (a
        // stale index above never prevents it). Stranded PROCESSING jobs are recovered on open (§25). The
        // productive worker (real OpenNLP/embedding, C3) is not started here yet; jobs persist until it runs.
        final com.aresstack.askai.research.knowledge.KnowledgeProcessingSettings knowledgeSettings =
                com.aresstack.askai.research.knowledge.KnowledgeProcessingSettings.defaults();
        final com.aresstack.askai.research.knowledge.FileSourceProcessingQueue processingQueue =
                new com.aresstack.askai.research.knowledge.FileSourceProcessingQueue(
                        new File(projectContext.getProjectDirectory(), "processing"));
        processingQueue.recoverStrandedJobs();
        acceptance.setCaptureAcceptedListener(
                new com.aresstack.askai.research.knowledge.SourceCaptureAcceptedListener() {
                    public void onCaptureAccepted(String captureId, String sourceId) {
                        processingQueue.enqueue(
                                new com.aresstack.askai.research.knowledge.SourceProcessingRequest(
                                        captureId, sourceId, knowledgeSettings.segmentationPipelineVersion,
                                        knowledgeSettings.embeddingModelFingerprint));
                    }
                });
        OoResearchStateMachine stateMachine = new OoResearchStateMachine(sessionKey);

        BrowserBridgeEndpoint bridge = null;
        com.aresstack.askai.research.mcp.ResearchControlEndpoint control = null;
        com.aresstack.askai.research.mcp.ResearchServiceEndpoint service = null;
        // 2. LAZY browser runtime: created STOPPED — selecting the Research agent must NOT spawn a browser
        // or block the EDT. The Playwright sidecar starts on the FIRST browser command (on the runtime's
        // own owner thread) and is stopped again when the browsing phase ends; a broken generation restarts.
        final BrowserRuntimePort browser = new LazyRestartableBrowserRuntime(config,
                SIDECAR_READY_TIMEOUT_SECONDS, sidecarConfigFile.getAbsolutePath(), toolClients);
        try {
            // 3. Host endpoints for this session+generation (the bridge forwards through the lazy runtime).
            bridge = new BrowserBridgeEndpoint(registry, browser, captures, sessionKey, generationId);
            bridge.open();

            // The control endpoint needs the live-state context; resources are created first with a
            // placeholder-free two-step wiring: build resources LAST, context reads their state.
            ProductiveResearchSessionResources[] holder = new ProductiveResearchSessionResources[1];
            final com.aresstack.askai.research.state.oo.ResearchStateMemento initial =
                    stateMachine.initialMemento();
            final com.aresstack.askai.research.mcp.ResearchControlContext controlContext =
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

                        @Override
                        public String acceptCapture(String captureId, String searchQuery) {
                            // Delegate WITH the query so the productive context persists it on the source.
                            return holder[0].controlContext().acceptCapture(captureId, searchQuery);
                        }

                        @Override
                        public String parkCandidate(String url, String title, String excerpt,
                                                    double rerankScore, String searchQuery) {
                            return holder[0].controlContext().parkCandidate(url, title, excerpt, rerankScore,
                                    searchQuery);
                        }
                    };
            control = new com.aresstack.askai.research.mcp.ResearchControlEndpoint(
                    registry, sessionKey, generationId, controlContext);
            // The INTERNAL service endpoint (manual_source_accept) shares the SAME acceptance context but its
            // own namespace — a user search accepts sources phase-independently, never as an agent tool.
            service = new com.aresstack.askai.research.mcp.ResearchServiceEndpoint(
                    registry, sessionKey, generationId, controlContext);

            // 4. Backend with BOTH endpoint descriptors (structured env hand-off; tokens never logged).
            String agentJava = config.getAgentJavaExecutable();
            // The agent reads the FULL settings document (AI/prompt settings stay host-side only in
            // the sense of never reaching the BROWSER process; the agent needs them) plus the MANDATORY
            // reranker start snapshot: the agent MUST rerank before opening any page.
            Map<String, String> baseEnv = agentLaunchEnvironment(fullConfigFile.getAbsolutePath(),
                    rerankerSnapshot.getAbsolutePath(), inferenceSnapshotPath, inferenceUnavailableReason,
                    searchStrategySnapshotPath);
            AgentLaunchSpec spec = new AgentLaunchSpec(agentJava,
                    java.util.Arrays.asList("-jar", config.getAgentJar()), baseEnv);
            AcpResearchSessionBackend backend = null; // assigned after control.open()

            ProductiveResearchSessionResources resources;
            control.open();
            service.open();
            String researchUrl = registry.endpointUrl(control.getHandle());
            AcpEndpointDescriptor researchDescriptor = new AcpEndpointDescriptor(
                    control.getEndpointId(), researchUrl, "streamable", control.getHandle().getToken());
            String bridgeUrl = registry.endpointUrl(bridge.getHandle());
            AcpEndpointDescriptor browserDescriptor = new AcpEndpointDescriptor(
                    bridge.getEndpointId(), bridgeUrl, "streamable", bridge.getHandle().getToken());
            String serviceUrl = registry.endpointUrl(service.getHandle());
            AcpEndpointDescriptor serviceDescriptor = new AcpEndpointDescriptor(
                    service.getEndpointId(), serviceUrl, "streamable", service.getHandle().getToken());
            backend = new AcpResearchSessionBackend(connector, spec, researchDescriptor, browserDescriptor,
                    serviceDescriptor);

            resources = new ProductiveResearchSessionResources(sessionKey, stateMachine, captures,
                    repository, acceptance, projectContext, control, bridge,
                    browser, backend, service);
            resources.setSearchProfile(profile);
            holder[0] = resources;
            control.refreshTools(); // now that the live context resolves, publish the initial tool set
            return resources;
        } catch (RuntimeException ex) {
            // The lazy browser runtime no longer starts a process here, so the only failures are endpoint
            // registration / agent-backend wiring (runtime) — rolled back the same way.
            rollback(control, service, bridge, browser);
            throw ex;
        }
    }

    private static void rollback(com.aresstack.askai.research.mcp.ResearchControlEndpoint control,
                                 com.aresstack.askai.research.mcp.ResearchServiceEndpoint service,
                                 BrowserBridgeEndpoint bridge, BrowserRuntimePort browser) {
        if (control != null) {
            control.close();
        }
        if (service != null) {
            service.close();
        }
        if (bridge != null) {
            bridge.close();
        }
        if (browser != null) {
            browser.close(); // STOPPED runtime → just releases the owner thread; started → tears the sidecar down
        }
    }

    /**
     * The agent launch environment: the browser-search config document AND the MANDATORY reranker start
     * snapshot. Extracted so the exact env the agent receives (both keys present) is unit-testable
     * without spawning a process.
     */
    static Map<String, String> agentLaunchEnvironment(String searchConfigPath,
                                                      String rerankerSnapshotPath,
                                                      String inferenceSnapshotPath) {
        return agentLaunchEnvironment(searchConfigPath, rerankerSnapshotPath, inferenceSnapshotPath, "");
    }

    static Map<String, String> agentLaunchEnvironment(String searchConfigPath,
                                                      String rerankerSnapshotPath,
                                                      String inferenceSnapshotPath,
                                                      String inferenceUnavailableReason) {
        return agentLaunchEnvironment(searchConfigPath, rerankerSnapshotPath, inferenceSnapshotPath,
                inferenceUnavailableReason, "");
    }

    static Map<String, String> agentLaunchEnvironment(String searchConfigPath,
                                                      String rerankerSnapshotPath,
                                                      String inferenceSnapshotPath,
                                                      String inferenceUnavailableReason,
                                                      String searchStrategySnapshotPath) {
        Map<String, String> baseEnv = new LinkedHashMap<String, String>();
        baseEnv.put("ASKAI_BROWSER_SEARCH_CONFIG", searchConfigPath);
        baseEnv.put("ASKAI_RERANKER_CONFIG", rerankerSnapshotPath);
        // OPTIONAL: only handed over when an inference descriptor was published (else omitted entirely, so
        // the agent's ResearchAgentEnvironment.hasInference() is false and it keeps the honest fallback).
        if (inferenceSnapshotPath != null && !inferenceSnapshotPath.trim().isEmpty()) {
            baseEnv.put("ASKAI_INFERENCE_CONFIG", inferenceSnapshotPath);
        } else if (inferenceUnavailableReason != null && !inferenceUnavailableReason.trim().isEmpty()) {
            // No descriptor: hand over the actionable REASON so the agent's MODEL_UNAVAILABLE message can
            // tell the user what to fix (e.g. select a chat model), never just "no descriptor configured".
            baseEnv.put("ASKAI_INFERENCE_UNAVAILABLE_REASON", inferenceUnavailableReason.trim());
        }
        // OPTIONAL: only for an API-provider selection; absent → the agent's documented legacy browser path.
        if (searchStrategySnapshotPath != null && !searchStrategySnapshotPath.trim().isEmpty()) {
            baseEnv.put("ASKAI_SEARCH_STRATEGY_CONFIG", searchStrategySnapshotPath);
        }
        return baseEnv;
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
