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
    /**
     * OPTIONAL: publishes the per-session EMBEDDING descriptor for the knowledge pipeline, or null. When null
     * (or no embedding model is configured) knowledge processing is a diagnosed UNAVAILABLE capability — the
     * acceptance hook enqueues nothing rather than tagging jobs with a fake embedding world.
     */
    private final com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider
            embeddingSnapshots;
    /** The initial-search strategy selection; legacy browser publishes NO snapshot (today's behavior). */
    private final SearchStrategySelection searchStrategy;
    /** The session's research language ISO code ("en"/"de") for the OpenNLP sentence resolver; default English. */
    private volatile String researchLanguageCode = "en";
    /** OPTIONAL host NLP snapshot provider; resolves the session's selected sentence model (else regex). */
    private volatile com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider nlpSnapshots;

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
        this(registry, toolClients, connector, config, generationId, browserSearchValues,
                browserSearchRevision, rerankerSnapshots, inferenceSnapshots, searchStrategy, null);
    }

    public ProductiveResearchBackendFactory(McpServerRegistry registry, McpToolClientFactory toolClients,
                                            AcpAgentConnector connector, ResearchRuntimeConfig config,
                                            long generationId, Map<String, String> browserSearchValues,
                                            long browserSearchRevision,
                                            RerankerConfigurationSnapshotProvider rerankerSnapshots,
                                            com.aresstack.askai.agent.model.inference
                                                    .InferenceConfigurationSnapshotProvider inferenceSnapshots,
                                            SearchStrategySelection searchStrategy,
                                            com.aresstack.askai.agent.model.embedding
                                                    .EmbeddingConfigurationSnapshotProvider embeddingSnapshots) {
        this.registry = registry;
        this.toolClients = toolClients;
        this.connector = connector;
        this.config = config;
        this.generationId = generationId;
        this.browserSearchValues = browserSearchValues;
        this.browserSearchRevision = browserSearchRevision;
        this.rerankerSnapshots = rerankerSnapshots;
        this.inferenceSnapshots = inferenceSnapshots;
        this.embeddingSnapshots = embeddingSnapshots;
        this.searchStrategy = searchStrategy == null
                ? SearchStrategySelection.legacyBrowser() : searchStrategy;
    }

    /** The session language (ISO code) for the knowledge worker's OpenNLP sentence resolver; empty → English. */
    public void setResearchLanguageCode(String languageCode) {
        this.researchLanguageCode = languageCode == null || languageCode.trim().isEmpty()
                ? "en" : languageCode.trim();
    }

    /** The host NLP snapshot provider; the session resolves its selected sentence model through it (else regex). */
    public void setNlpConfigurationSnapshotProvider(
            com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider provider) {
        this.nlpSnapshots = provider;
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
            // The SAME session research language the search/NLP path sees: the provider MAY resolve a
            // language-appropriate reranker (today: the one configured selection serves both, visibly logged).
            rerankerSnapshot = rerankerSnapshots.prepareForSession(sessionKey, projectDir,
                    config.getSelectedRerankerModel(), researchLanguageCode);
        } catch (RerankerConfigurationException ex) {
            throw new IOException("The mandatory reranker could not be prepared for this session: "
                    + ex.getMessage(), ex);
        }
        // Per-LANGUAGE reranker snapshots (both resolve the same selected model today): the runtime picks the
        // search's language snapshot per manual search, so the NEXT search after a language switch uses its
        // language's reranker WITHOUT a host round-trip or a session restart; a running search keeps its
        // instance. Fail-fast like the mandatory default above — never a silent cross-language fallback.
        final String rerankerEnPath;
        final String rerankerDePath;
        try {
            File rerankerEnDir = new File(projectDir, "reranker-lang/en");
            File rerankerDeDir = new File(projectDir, "reranker-lang/de");
            rerankerEnDir.mkdirs();
            rerankerDeDir.mkdirs();
            rerankerEnPath = rerankerSnapshots.prepareForSession(sessionKey, rerankerEnDir,
                    config.getSelectedRerankerModel(), "en").getAbsolutePath();
            rerankerDePath = rerankerSnapshots.prepareForSession(sessionKey, rerankerDeDir,
                    config.getSelectedRerankerModel(), "de").getAbsolutePath();
        } catch (RerankerConfigurationException ex) {
            throw new IOException("The per-language reranker snapshots could not be prepared: "
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
        final com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings knowledgeSettings =
                com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings.defaults();
        final com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue processingQueue =
                new com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue(
                        new File(projectContext.getProjectDirectory(), "processing"));
        processingQueue.recoverStrandedJobs();
        // The SESSION resolves ONE authoritative embedding world (host descriptor) and stamps every job with
        // its fingerprint. This is done once, at session build — never re-looked-up per capture — so a later
        // global model switch cannot mix vector worlds mid-session (§4.3). When no embedding model is
        // configured the capability is UNAVAILABLE: the acceptance hook enqueues nothing (no fake-world jobs,
        // never a false COMPLETED). The plugin holds ONLY the neutral scheduler port; queue/worker/NLP live in
        // :research-knowledge-processing. The productive worker is started with C4 (Variant B).
        com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor embeddingDescriptor =
                prepareEmbeddingDescriptor(sessionKey, projectDir);
        final com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingRunner[] knowledgeRunner =
                {null};
        final com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner[]
                projectionRunner = {null};
        final com.aresstack.askai.research.knowledge.processing.live.KnowledgeProjectionInvalidator[]
                projectionInvalidator = {null};
        final KnowledgeProcessingSessionFactory.OutlineStalenessCheck[] outlineStaleness = {null};
        // Set once the session resources exist, so the projection listener can notify an open view.
        final ProductiveResearchSessionResources[] resourcesRef = {null};
        if (embeddingDescriptor != null) {
            // Compose (but do not start) the productive worker for THIS session's embedding world (C4 lifts
            // Variant B). The scheduler stamps jobs with the descriptor fingerprint and wakes the worker so an
            // accepted source is processed promptly rather than at the next idle poll.
            // Sentence segmenters resolve PER JOB LANGUAGE from the host NLP snapshot (OpenNLP over the
            // selected model's artifact, or the regex fallback; a tampered/checksum-mismatch model fails
            // HARD). The session language is eagerly resolved inside buildRunner (fail-fast + ready line);
            // the other language resolves lazily when the first job of that language actually runs.
            final com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider nlp = nlpSnapshots;
            // The confirmed scope (question + focus areas) anchors the projection's gap analysis.
            java.util.List<String> briefQuestions = new java.util.ArrayList<String>();
            com.aresstack.askai.research.store.MetadataLoadResult scopeMetadata =
                    projectContext.getMetadataStore().load(sessionKey);
            if (scopeMetadata.getStatus()
                    == com.aresstack.askai.research.store.MetadataLoadResult.Status.LOADED) {
                com.aresstack.askai.research.store.ResearchProjectMetadata scope =
                        scopeMetadata.getMetadata();
                if (scope.hasResearchQuestion()) {
                    briefQuestions.add(scope.getResearchQuestion());
                }
                briefQuestions.addAll(scope.getConfirmedFocusAreas());
            }
            KnowledgeProcessingSessionFactory.KnowledgeSession knowledgeSession =
                    KnowledgeProcessingSessionFactory.buildRunner(
                    projectContext.getProjectDirectory(), sessionKey, embeddingDescriptor,
                    researchLanguageCode,
                    new KnowledgeProcessingSessionFactory.SentenceSegmenterResolver() {
                        public SessionSentenceSegmenter resolve(String languageCode) {
                            return SessionSentenceSegmenter.resolve(nlp, languageCode);
                        }
                    },
                    captures, repository, processingQueue, knowledgeSettings, briefQuestions,
                    new KnowledgeProcessingSessionFactory.ProjectionListener() {
                        public void onProjectionUpdated(
                                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection projection) {
                            // Render the projection into the "outline" artifact slot (a visible LIVE view,
                            // no approval anywhere) and let the session refresh an open view. Best-effort:
                            // an artifact write failure only costs THIS render — the persisted projection
                            // is already durable and the next rebuild retries.
                            try {
                                String markdown = com.aresstack.askai.research.knowledge.live
                                        .LiveOutlineMarkdown.render(projection);
                                com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent current =
                                        projectContext.getArtifactStore().read("outline");
                                projectContext.getArtifactStore().replace("outline",
                                        current.getRevision(), markdown);
                            } catch (RuntimeException renderFailed) {
                                System.err.println("[research-knowledge] live outline artifact write "
                                        + "failed: " + renderFailed.getMessage());
                            }
                            if (resourcesRef[0] != null) {
                                resourcesRef[0].fireProjectionUpdated();
                            }
                        }
                    },
                    new Runnable() {
                        public void run() {
                            // A COMPLETED passage job only refreshes an open view (staleness re-check) —
                            // it never rebuilds topics or the outline (issue #29).
                            if (resourcesRef[0] != null) {
                                resourcesRef[0].fireProjectionUpdated();
                            }
                        }
                    });
            knowledgeRunner[0] = knowledgeSession.worker;
            projectionRunner[0] = knowledgeSession.projection;
            projectionInvalidator[0] = knowledgeSession.invalidator;
            outlineStaleness[0] = knowledgeSession.staleness;
            final com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingScheduler base =
                    new com.aresstack.askai.research.knowledge.processing
                            .QueueBackedKnowledgeProcessingScheduler(processingQueue, knowledgeSettings,
                            embeddingDescriptor.embeddingFingerprint());
            acceptance.setKnowledgeProcessingScheduler(
                    new com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingScheduler() {
                        public void enqueue(String captureId, String sourceId) {
                            enqueue(captureId, sourceId, "");
                        }

                        @Override
                        public void enqueue(String captureId, String sourceId, String languageCode) {
                            // The acceptance-time language snapshot wins; a caller without one (agent path /
                            // legacy) gets the session language — resolved HERE at the composition root.
                            base.enqueue(captureId, sourceId,
                                    languageCode == null || languageCode.trim().isEmpty()
                                            ? researchLanguageCode : languageCode);
                            knowledgeRunner[0].wake();
                        }
                    });
        }
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
                        public String acceptCapture(String captureId, String searchQuery, boolean userRelevant) {
                            // Delegate WITH the ⭐ flag so the productive context persists it on the source.
                            return holder[0].controlContext().acceptCapture(captureId, searchQuery, userRelevant);
                        }

                        @Override
                        public String acceptCapture(String captureId, String searchQuery, boolean userRelevant,
                                                    String languageCode) {
                            // Delegate WITH the language snapshot so the knowledge job carries its world.
                            return holder[0].controlContext().acceptCapture(captureId, searchQuery,
                                    userRelevant, languageCode);
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
            // The INTERNAL service endpoint (manual_source_accept + the explicit derived actions, #33)
            // shares the SAME acceptance context but its own namespace — user/host/test operations,
            // never agent tools. The derived actions resolve through the session at call time.
            // The runtime-plumbing endpoint (manual_source_*) — internal runtime->host traffic only.
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
            baseEnv.put("ASKAI_RERANKER_CONFIG_EN", rerankerEnPath);
            baseEnv.put("ASKAI_RERANKER_CONFIG_DE", rerankerDePath);
            // DEV/TEST-only hand-off (mirrors askai.research.sidecar.args for the browser sidecar): extra JVM
            // args for the research-agent-runtime child, inserted BEFORE -jar so they are JVM flags. Empty by
            // default → no production effect. Set on the HOST JVM, e.g. to A/B the overlay:
            //   -Daskai.research.agent.jvmargs=-Daskai.research.hud.enabled=false
            java.util.List<String> agentArgs = new java.util.ArrayList<String>();
            String extraJvmArgs = System.getProperty("askai.research.agent.jvmargs", "").trim();
            if (!extraJvmArgs.isEmpty()) {
                for (String extra : extraJvmArgs.split("\\s+")) {
                    agentArgs.add(extra);
                }
            }
            agentArgs.add("-jar");
            agentArgs.add(config.getAgentJar());
            AgentLaunchSpec spec = new AgentLaunchSpec(agentJava, agentArgs, baseEnv);
            AcpResearchSessionBackend backend = null; // assigned after control.open()

            ProductiveResearchSessionResources resources;
            control.open();
            service.open();
            // The BOT-CONTROL endpoint: exactly run_command/session_state/chat_history over the session's
            // command processor (resolved at call time). This — and only this — goes to external clients.
            final com.aresstack.askai.research.mcp.ResearchBotControlEndpoint botControl =
                    new com.aresstack.askai.research.mcp.ResearchBotControlEndpoint(
                            registry, sessionKey, generationId,
                            new com.aresstack.askai.research.mcp.ResearchBotControlEndpoint.SessionGateway() {
                                public String execute(String command, String arguments) {
                                    com.aresstack.askai.research.mcp.ResearchBotControlEndpoint
                                            .SessionGateway gateway = holder[0] == null ? null
                                            : holder[0].getSessionGateway();
                                    return gateway == null ? null : gateway.execute(command, arguments);
                                }

                                public String describeState() {
                                    com.aresstack.askai.research.mcp.ResearchBotControlEndpoint
                                            .SessionGateway gateway = holder[0] == null ? null
                                            : holder[0].getSessionGateway();
                                    return gateway == null ? null : gateway.describeState();
                                }

                                public String describeHistory(boolean raw) {
                                    com.aresstack.askai.research.mcp.ResearchBotControlEndpoint
                                            .SessionGateway gateway = holder[0] == null ? null
                                            : holder[0].getSessionGateway();
                                    return gateway == null ? null : gateway.describeHistory(raw);
                                }
                            });
            botControl.open();
            String researchUrl = registry.endpointUrl(control.getHandle());
            AcpEndpointDescriptor researchDescriptor = new AcpEndpointDescriptor(
                    control.getEndpointId(), researchUrl, "streamable", control.getHandle().getToken());
            String bridgeUrl = registry.endpointUrl(bridge.getHandle());
            AcpEndpointDescriptor browserDescriptor = new AcpEndpointDescriptor(
                    bridge.getEndpointId(), bridgeUrl, "streamable", bridge.getHandle().getToken());
            String serviceUrl = registry.endpointUrl(service.getHandle());
            AcpEndpointDescriptor serviceDescriptor = new AcpEndpointDescriptor(
                    service.getEndpointId(), serviceUrl, "streamable", service.getHandle().getToken());
            // Issue #33: DEV hand-off for HEADLESS clients (gates, tests, an MCP-driving AI): the service
            // endpoint's connection data as a file under the project directory. Localhost-only endpoint,
            // per-session token, invalidated on close — the file merely makes the explicit user/host
            // actions scriptable without the GUI. Overwritten on every session start (stale after close).
            String botUrl = registry.endpointUrl(botControl.getHandle());
            writeUtf8(new File(projectDir, "service-endpoint.json"),
                    com.aresstack.askai.research.mcp.ServiceEndpointDescriptorFile.toJson(
                            botControl.getEndpointId(), botUrl, "streamable",
                            botControl.getHandle().getToken()));
            backend = new AcpResearchSessionBackend(connector, spec, researchDescriptor, browserDescriptor,
                    serviceDescriptor);

            // The UI-facing repository only REFRESHES an open view on a successful update (Save/Exclude/star)
            // so the outline tab re-checks its staleness — it never triggers a rebuild (issue #29).
            resources = new ProductiveResearchSessionResources(sessionKey, stateMachine, captures,
                    new NotifyingSourceRepository(repository, new Runnable() {
                        public void run() {
                            if (resourcesRef[0] != null) {
                                resourcesRef[0].fireProjectionUpdated();
                            }
                        }
                    }),
                    acceptance, projectContext, control, bridge,
                    browser, backend, service);
            resources.setSearchProfile(profile);
            resources.setBotControlEndpoint(botControl);
            holder[0] = resources;
            resourcesRef[0] = resources;
            control.refreshTools(); // now that the live context resolves, publish the initial tool set
            // Start the continuous knowledge worker LAST, once everything else is wired: it drains the
            // recovered persistent FIFO and processes newly accepted sources until the session closes.
            if (knowledgeRunner[0] != null) {
                knowledgeRunner[0].start();
                resources.setKnowledgeRunner(knowledgeRunner[0]);
                if (projectionRunner[0] != null) {
                    // The projection runner thread only WAITS for an explicit trigger — session open never
                    // invalidates or rebuilds the outline anymore (issue #29). The persisted projection is
                    // simply displayed; "Inhaltsverzeichnis erzeugen" is the ONLY rebuild trigger.
                    projectionRunner[0].start();
                    resources.setProjectionRunner(projectionRunner[0]);
                    resources.setOutlineStaleness(outlineStaleness[0]);
                }
            }
            return resources;
        } catch (RuntimeException ex) {
            // The lazy browser runtime no longer starts a process here, so the only failures are endpoint
            // registration / agent-backend wiring (runtime) — rolled back the same way.
            if (projectionRunner[0] != null) {
                projectionRunner[0].stop(); // safe even if never started
            }
            if (knowledgeRunner[0] != null) {
                knowledgeRunner[0].stop(); // safe even if never started
            }
            rollback(control, service, bridge, browser);
            throw ex;
        }
    }

    /**
     * Resolve THE embedding world for this session (§4.3): validate the configured embedding model, start its
     * runtime, probe its dimension and freeze an immutable {@code EmbeddingEndpointDescriptor}. This snapshot
     * is authoritative for the whole session and is never re-looked-up per capture. Returns {@code null} when
     * the capability is genuinely unavailable (no provider, or no/incompatible embedding model configured):
     * knowledge processing is then a diagnosed no-op rather than silently falling back to a fake embedder.
     */
    private com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor prepareEmbeddingDescriptor(
            String sessionKey, File projectDir) {
        if (embeddingSnapshots == null) {
            System.err.println("[research-knowledge] embedding capability UNAVAILABLE: this host publishes no "
                    + "embedding provider — accepted sources are not queued for knowledge processing.");
            return null;
        }
        try {
            // The central AskAI selection (ai.embeddingsModel) is authoritative host-side; "" lets the host
            // provider fill it in. An empty/removed/incompatible selection is a typed exception below.
            com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot snapshot =
                    embeddingSnapshots.prepareForSession(sessionKey, projectDir, "");
            return snapshot.descriptor;
        } catch (com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException ex) {
            System.err.println("[research-knowledge] embedding capability UNAVAILABLE (" + ex.getReason()
                    + "): " + ex.getMessage() + " — accepted sources are not queued for knowledge processing.");
            return null;
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
