package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.solon.SolonAcpAgentConnector;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClientFactory;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.mcp.solon.SolonMcpToolClientFactory;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerModelCatalog;
import com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.localmodels.HttpEmbeddingDimensionProbe;
import com.aresstack.askai.java8.localmodels.LocalActiveResearchSessionRegistry;
import com.aresstack.askai.java8.localmodels.LocalEmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalEmbeddingModelCatalog;
import com.aresstack.askai.java8.localmodels.LocalEmbeddingRuntime;
import com.aresstack.askai.java8.localmodels.LocalInferenceConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager;
import com.aresstack.askai.java8.localmodels.LocalRerankerConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalRerankerModelCatalog;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app-level owner of the host runtime services handed to agent plugins via
 * {@code AgentHostContext.getService}: the Solon MCP server runtime (LAZY — the loopback HTTP server only
 * starts when a plugin actually registers an endpoint, i.e. when the productive research mode is used), the
 * MCP tool-client factory and the ACP agent connector. One instance per application; {@link #shutdown()}
 * stops the MCP runtime if it was ever started.
 */
public final class AgentRuntimeServices {

    private final LazyRegistry registry = new LazyRegistry();
    private final McpToolClientFactory toolClients = new SolonMcpToolClientFactory();
    // The agent's STDERR goes to the app console: without it a failed agent start is undiagnosable.
    private final AcpAgentConnector connector = new SolonAcpAgentConnector(Duration.ofSeconds(180),
            new java.util.function.Consumer<String>() {
                public void accept(String line) {
                    System.err.println("[research-agent] " + line);
                }
            });
    /** Publishes the mandatory per-session reranker snapshot from the local model runtime. */
    private final RerankerConfigurationSnapshotProvider rerankerSnapshots;
    /** Lists the installed rerank-capable models for the EXPLICIT selection in the settings UI. */
    private final RerankerModelCatalog rerankerCatalog;
    /** Publishes the per-session structured-inference descriptor from the central main model (optional). */
    private final InferenceConfigurationSnapshotProvider inferenceSnapshots;
    /** Publishes the per-session EMBEDDING descriptor for the continuous knowledge pipeline (optional). */
    private final EmbeddingConfigurationSnapshotProvider embeddingSnapshots;
    /** Tracks running research sessions so their descriptors can be re-published on a model change. */
    private final LocalActiveResearchSessionRegistry activeSessions;

    /** The embedding descriptor request timeout (local sidecar; probe + calls). */
    private static final long EMBEDDING_TIMEOUT_MILLIS = 60_000L;

    /** @deprecated retained only for callers without the local model runtime (no reranker service). */
    @Deprecated
    public AgentRuntimeServices() {
        this(null);
    }

    /** @deprecated legacy overload without the central config; the reranker selection is then plugin-driven. */
    @Deprecated
    public AgentRuntimeServices(LocalModelRuntimeManager localModelRuntime) {
        this(localModelRuntime, null);
    }

    /**
     * @param localModelRuntime the app's local model runtime manager; when present a mandatory reranker
     *                          snapshot provider is published for productive research sessions
     * @param centralConfig     the central AskAI configuration store; the reranker snapshot provider takes
     *                          its selection from {@code ai.rerankerModel} (AskAI → Configuration → AI models)
     */
    public AgentRuntimeServices(LocalModelRuntimeManager localModelRuntime,
                                AppConfigurationRepository centralConfig) {
        this.rerankerSnapshots = localModelRuntime == null ? null
                : new LocalRerankerConfigurationSnapshotProvider(localModelRuntime, centralConfig);
        this.rerankerCatalog = localModelRuntime == null ? null
                : new LocalRerankerModelCatalog(localModelRuntime);
        // Inference uses the central main model, which may live on the LOCAL sidecar or a REMOTE Ollama —
        // so it is published whenever there is a central config, even without a local runtime.
        this.inferenceSnapshots = centralConfig == null ? null
                : new LocalInferenceConfigurationSnapshotProvider(localModelRuntime, centralConfig);
        // The embedding descriptor is served from the LOCAL sidecar (its own catalog + dimension probe), so it
        // is published only alongside a local runtime — exactly like the reranker.
        this.embeddingSnapshots = localModelRuntime == null ? null
                : embeddingProvider(localModelRuntime, centralConfig);
        // The active-session registry re-publishes descriptors on a central model change; it needs a
        // central config to know what to publish and is only useful alongside the snapshot providers.
        this.activeSessions = centralConfig == null ? null
                : new LocalActiveResearchSessionRegistry(inferenceSnapshots, rerankerSnapshots, centralConfig);
    }

    /**
     * The productive embedding snapshot provider: the {@link LocalEmbeddingConfigurationSnapshotProvider} over
     * the local catalog + runtime + dimension probe, wrapped so the EXPLICIT selection comes from the central
     * {@code ai.embeddingsModel} (AskAI → Configuration → AI models) — mirroring the reranker's central
     * authority. A plugin-passed selection is only a transitional fallback. There is NO guessing: an empty /
     * removed / incompatible selection is a typed {@link EmbeddingConfigurationException}, never a "first found".
     */
    private static EmbeddingConfigurationSnapshotProvider embeddingProvider(
            LocalModelRuntimeManager localModelRuntime, final AppConfigurationRepository centralConfig) {
        final EmbeddingConfigurationSnapshotProvider delegate =
                new LocalEmbeddingConfigurationSnapshotProvider(
                        new LocalEmbeddingModelCatalog(localModelRuntime),
                        LocalEmbeddingRuntime.over(localModelRuntime),
                        new HttpEmbeddingDimensionProbe((int) EMBEDDING_TIMEOUT_MILLIS),
                        EMBEDDING_TIMEOUT_MILLIS);
        if (centralConfig == null) {
            return delegate; // legacy: trust the plugin-passed selection
        }
        return new EmbeddingConfigurationSnapshotProvider() {
            public EmbeddingConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                                    String selectedModel)
                    throws EmbeddingConfigurationException {
                String central = centralConfig.load().getAiModelSelections().getEmbeddingsModel();
                String centralTrimmed = central == null ? "" : central.trim();
                String effective = !centralTrimmed.isEmpty() ? centralTrimmed
                        : (selectedModel == null ? "" : selectedModel.trim());
                return delegate.prepareForSession(sessionId, sessionDirectory, effective);
            }
        };
    }

    /** The running-session registry, for AskAI to trigger a descriptor refresh on a model change. */
    public LocalActiveResearchSessionRegistry activeSessionRegistry() {
        return activeSessions;
    }

    /** The service map for DefaultAgentHostContext (neutral interface types as keys). */
    public Map<Class<?>, Object> asServiceMap() {
        Map<Class<?>, Object> services = new LinkedHashMap<Class<?>, Object>();
        services.put(McpServerRegistry.class, registry);
        services.put(McpToolClientFactory.class, toolClients);
        services.put(AcpAgentConnector.class, connector);
        if (rerankerSnapshots != null) {
            services.put(RerankerConfigurationSnapshotProvider.class, rerankerSnapshots);
        }
        if (rerankerCatalog != null) {
            services.put(RerankerModelCatalog.class, rerankerCatalog);
        }
        if (inferenceSnapshots != null) {
            services.put(InferenceConfigurationSnapshotProvider.class, inferenceSnapshots);
        }
        if (embeddingSnapshots != null) {
            services.put(EmbeddingConfigurationSnapshotProvider.class, embeddingSnapshots);
        }
        if (activeSessions != null) {
            services.put(ActiveResearchSessionRegistry.class, activeSessions);
        }
        return services;
    }

    public void shutdown() {
        registry.shutdown();
        // FINAL app teardown: also stop the process-global Solon server so its non-daemon HTTP-Dispatcher
        // thread dies and the JVM exits naturally. Without this the app hangs on close once research booted
        // Solon; without the plugin Solon never boots and the app already exits cleanly.
        SolonMcpServerRuntime.stopSharedServer();
    }

    /** Starts the real Solon MCP runtime on first use; thread-safe; idempotent shutdown. */
    private static final class LazyRegistry implements McpServerRegistry {
        private volatile SolonMcpServerRuntime runtime;

        private SolonMcpServerRuntime runtime() {
            SolonMcpServerRuntime current = runtime;
            if (current == null) {
                synchronized (this) {
                    if (runtime == null) {
                        runtime = new SolonMcpServerRuntime();
                    }
                    current = runtime;
                }
            }
            return current;
        }

        public McpEndpointHandle registerEndpoint(McpEndpointDefinition definition) {
            return runtime().registerEndpoint(definition);
        }

        public void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools) {
            runtime().updateTools(handle, tools);
        }

        public void unregisterEndpoint(McpEndpointHandle handle) {
            runtime().unregisterEndpoint(handle);
        }

        public String endpointUrl(McpEndpointHandle handle) {
            return runtime().endpointUrl(handle);
        }

        void shutdown() {
            SolonMcpServerRuntime current = runtime;
            if (current != null) {
                current.shutdown();
            }
        }
    }
}
