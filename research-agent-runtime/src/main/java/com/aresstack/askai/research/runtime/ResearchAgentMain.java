package com.aresstack.askai.research.runtime;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The external Solon research agent process. It mirrors host state and NEVER owns a research state machine —
 * the plugin/host is the only transition authority; this process only calls the MCP tools it is offered.
 *
 * <p>Readiness is REAL, not configured: {@code session/new} connects the research-control MCP endpoint, runs
 * {@code tools/list} and calls {@code research_status()}; only if that round-trip succeeds does session
 * creation succeed (otherwise the host start fails atomically). The first prompt turn then reports
 * {@code RESEARCH_MCP_READY} (and {@code BROWSER_NOT_AVAILABLE} when no browser endpoint exists — visible,
 * never fatal, never a silent fallback). ACP carries prompt/streaming/status/errors; MCP carries the research
 * tools — the same operations are never doubled as custom ACP requests. Logs go to STDERR only.</p>
 */
@AcpAgent(name = "askai-research-agent", version = "0.1")
public final class ResearchAgentMain {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ResearchAgentEnvironment environment;
    private McpClientProvider researchMcp;
    private volatile boolean readinessAnnounced;
    /**
     * The MANDATORY reranker, built ONCE at session/new for a browser session (validated + endpoint
     * readiness-checked there, not mid-prompt). Null only when this session has no browser endpoint.
     */
    private com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker;
    /**
     * The initial-search strategy chosen by the session snapshot, built ONCE at session/new (like the
     * reranker) so a running session never switches strategy. Null means "keep the loop's default legacy
     * browser strategy" — the unchanged browser SERP path.
     */
    private com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy;

    /** Outer bound the adapter waits on a provider future; beyond the client's own request timeout. */
    private static final long SEARCH_PROVIDER_TIMEOUT_MILLIS = 90_000L;
    /**
     * Runtime-scoped async search-provider registry: ONE {@code WebSearchProvidersModule} (and its
     * AsyncHttpClients) for the whole agent process, opened lazily when an API_PROVIDER strategy is first
     * needed and closed once at JVM shutdown. Never one client per research run.
     */
    private com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry
            searchProviderRegistry;
    /**
     * Canonical URLs visited across ALL runs of this agent process (one process per session): a
     * CONTINUE_RESEARCH turn gets a fresh budget but never navigates the same target pages again.
     */
    private final java.util.Set<String> visitedAcrossRuns =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    public static void main(String[] args) {
        System.err.println("[research-agent] starting");
        AcpAgentSupport.create(new ResearchAgentMain())
                .transport(new StdioAcpAgentTransport())
                .build().run();
        System.err.println("[research-agent] terminated");
    }

    @Initialize
    public AcpSchema.InitializeResponse initialize() {
        System.err.println("[research-agent] initialize");
        environment = ResearchAgentEnvironment.from(System.getenv());
        System.err.println("[research-agent] " + environment); // toString never contains tokens
        return AcpSchema.InitializeResponse.ok();
    }

    @NewSession
    public AcpSchema.NewSessionResponse newSession() {
        // Real readiness check: connect research MCP, list tools, call research_status. A failure here fails
        // session/new, so the host start is atomic (no half-started session).
        researchMcp = McpClientProvider.builder()
                .apiUrl(environment.researchUrl)
                .channel(environment.researchTransport)
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(15))
                .build();
        boolean hasStatus = false;
        for (FunctionTool tool : researchMcp.getTools()) {
            if ("research_status".equals(tool.name())) {
                hasStatus = true;
            }
        }
        if (!hasStatus) {
            throw new IllegalStateException("research_status is not offered by the research MCP endpoint");
        }
        ToolResult status = researchMcp.callTool("research_status",
                Collections.<String, Object>emptyMap());
        System.err.println("[research-agent] research_status ok: " + status);

        // A browser research session REQUIRES the mandatory reranker. Build and readiness-check it here,
        // at session/new — a missing/invalid snapshot or an unreachable endpoint fails the session start
        // atomically, never mid-run and never with a silent raw-order fallback.
        if (environment.hasBrowser()) {
            this.reranker = buildMandatoryReranker();
            // Fix the initial-search strategy for the whole session here (null → unchanged browser path).
            this.searchStrategy = buildSearchStrategy();
        }
        return new AcpSchema.NewSessionResponse("research-acp-" + environment.sessionId, null, null);
    }

    /**
     * Read + strictly validate the reranker start snapshot, confirm the endpoint is reachable, and build
     * the single {@link com.aresstack.askai.research.runtime.rerank.SearchResultReranker} for this
     * session. Every failure is a hard session/new failure.
     */
    private com.aresstack.askai.research.runtime.rerank.CandidateReranker buildMandatoryReranker() {
        if (!environment.hasReranker()) {
            throw new IllegalStateException("ASKAI_RERANKER_CONFIG is required for a browser research "
                    + "session (the local reranker is mandatory; no raw-order fallback).");
        }
        com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument document;
        try {
            document = com.aresstack.askai.research.runtime.rerank.RerankerConfigurationLoader
                    .load(environment.rerankerConfigPath);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("The reranker configuration is unusable ("
                    + environment.rerankerConfigPath + "): " + ex.getMessage(), ex);
        }
        com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor descriptor =
                document.descriptor;
        if (!descriptor.hasCapability(
                com.aresstack.askai.agent.model.reranker.RerankerCapability.RERANK)) {
            throw new IllegalStateException("The reranker endpoint does not advertise RERANK.");
        }
        if (descriptor.scoreSemantics
                != com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics.RAW_LOGIT) {
            throw new IllegalStateException("The reranker endpoint must use RAW_LOGIT scores (was "
                    + descriptor.scoreSemantics + ").");
        }
        com.aresstack.askai.research.runtime.rerank.HttpRerankerClient client =
                new com.aresstack.askai.research.runtime.rerank.HttpRerankerClient(descriptor);
        try {
            client.probeReadiness();
        } catch (com.aresstack.askai.research.runtime.rerank.RerankerClientException ex) {
            throw new IllegalStateException("The reranker endpoint is not ready: " + ex.getMessage(),
                    ex);
        }
        com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy policy =
                new com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy(
                        descriptor.selectionConfiguration);
        System.err.println("[research-agent] reranker ready: " + descriptor.modelName);
        return new com.aresstack.askai.research.runtime.rerank.SearchResultReranker(client, policy,
                descriptor.modelName, descriptor.scoreSemantics);
    }

    /**
     * Read the initial-search strategy snapshot (path named by {@code ASKAI_SEARCH_STRATEGY_CONFIG}) and
     * build the chosen strategy ONCE for this session. Absent config → {@code null} (the loop keeps its
     * unchanged browser strategy). A present-but-invalid snapshot is a hard session/new failure — never a
     * silent fallback to the legacy browser search.
     */
    private com.aresstack.askai.research.runtime.search.SearchStrategy buildSearchStrategy() {
        if (!environment.hasSearchStrategyConfig()) {
            return null;
        }
        String json;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(environment.searchStrategyConfigPath));
            json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read ASKAI_SEARCH_STRATEGY_CONFIG="
                    + environment.searchStrategyConfigPath + ": " + ex.getMessage(), ex);
        }
        com.aresstack.askai.research.runtime.search.SearchStrategyConfiguration config =
                com.aresstack.askai.research.runtime.search.SearchStrategyConfigurationLoader.parse(json);
        // Only an API_PROVIDER selection needs the (runtime-scoped) provider registry; a legacy-browser
        // selection never opens the module or touches the provider files.
        com.aresstack.askai.research.runtime.search.provider.SearchProviderRegistry registry =
                config.getStrategy()
                        == com.aresstack.askai.research.runtime.search.StrategySelection.API_PROVIDER
                        ? ensureSearchProviderRegistry() : null;
        com.aresstack.askai.research.runtime.search.SearchStrategy strategy =
                com.aresstack.askai.research.runtime.search.ResearchSearchStrategyFactory.create(
                        config, registry);
        System.err.println("[research-agent] initial search: " + config.getStrategy()
                + (strategy == null ? " (legacy browser)" : " / " + config.getProviderId() + " / "
                        + config.getEngine()));
        return strategy;
    }

    /**
     * Lazily open the single runtime-scoped async provider registry and register its idempotent
     * {@code close()} on the JVM shutdown hook IMMEDIATELY, so even a later initialization failure still
     * shuts the AsyncHttpClients down cleanly. Missing provider files are not fatal — the module opens
     * resiliently and a selected-but-unconfigured provider surfaces a typed error at search time.
     */
    private synchronized
            com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry
            ensureSearchProviderRegistry() {
        if (searchProviderRegistry == null) {
            final com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry
                    registry = new com.aresstack.askai.research.runtime.search.provider.async
                            .AsyncSearchProviderRegistry(
                                    com.aresstack.askai.research.runtime.search.provider.async
                                            .ModuleAsyncSearchGenerationFactory
                                            .userHome(SEARCH_PROVIDER_TIMEOUT_MILLIS));
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    registry.close();
                }
            }, "research-search-registry-close"));
            searchProviderRegistry = registry;
        }
        return searchProviderRegistry;
    }

    @Cancel
    public void cancel() {
        System.err.println("[research-agent] cancel");
        cancelled.set(true);
    }

    @Prompt
    public AcpSchema.PromptResponse prompt(SyncPromptContext ctx, AcpSchema.PromptRequest request) {
        System.err.println("[research-agent] prompt turn started");
        cancelled.set(false);
        if (!readinessAnnounced) {
            readinessAnnounced = true;
            // Readiness is a technical fact, not conversation: technical details only, never a bubble.
            ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("RESEARCH_MCP_READY"));
            if (!environment.hasBrowser()) {
                ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .log("BROWSER_NOT_AVAILABLE"));
            }
        }
        String text = request.text() == null ? "" : request.text();
        ctx.sendThought("planning: " + text);
        if (cancelled.get()) {
            return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
        }
        // Autonomous web research turn: a NORMAL user question starts the loop whenever the HOST state
        // machine (mirrored via research_status — this process owns no state) is in research/running.
        // The legacy explicit "research:" prefix keeps working. Without a browser endpoint this is a
        // visible, honest refusal — never a fallback.
        boolean explicitResearch = text.startsWith("research:");
        String task = explicitResearch ? text.substring("research:".length()).trim() : text.trim();
        if ((explicitResearch || hostIsInResearchRunning()) && !task.isEmpty()) {
            if (!environment.hasBrowser()) {
                // An honest, STRUCTURED refusal: the host renders a readable result card from it.
                ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .log("BROWSER_NOT_AVAILABLE: cannot run autonomous web research this turn."));
                ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire.outcome(
                        new com.aresstack.askai.research.runtime.loop.ResearchRunOutcome(
                                com.aresstack.askai.research.runtime.loop.ResearchStopReason.MCP_UNAVAILABLE,
                                0, 0, 0, 0, 0, true,
                                com.aresstack.askai.research.runtime.loop.ResearchRunOutcome
                                        .Limitation.NONE,
                                com.aresstack.askai.research.runtime.loop.ResearchRunOutcome
                                        .RecommendedAction.RETRY)));
                return AcpSchema.PromptResponse.endTurn();
            }
            runResearchLoop(ctx, task);
            return cancelled.get()
                    ? new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED)
                    : AcpSchema.PromptResponse.endTurn();
        }
        // Mirror the host state via MCP (no own state machine): technical details only — the HOST leads
        // the conversation; this process never small-talks in the user's chat.
        try {
            ToolResult status = researchMcp.callTool("research_status",
                    Collections.<String, Object>emptyMap());
            ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("status: " + status));
        } catch (RuntimeException ex) {
            ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("research MCP unavailable: " + ex.getMessage()));
        }
        if (text.contains("slow")) {
            for (int i = 0; i < 1_000_000 && !cancelled.get(); i++) {
                ctx.sendMessage("working " + i);
            }
            if (cancelled.get()) {
                return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
            }
        }
        ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                .log("turn done for: " + text));
        return AcpSchema.PromptResponse.endTurn();
    }

    /** Mirror the host state: research phase in run state — the only condition for an autonomous turn. */
    private boolean hostIsInResearchRunning() {
        try {
            String status = String.valueOf(researchMcp.callTool("research_status",
                    Collections.<String, Object>emptyMap()));
            return status.contains("research/running");
        } catch (RuntimeException ex) {
            return false; // unreachable status → answer as a plain turn, never start a blind run
        }
    }

    /**
     * The 36A loop, verbatim: content-driven, centrally budgeted, PHASE_READY as an EVENT line (the host
     * remains the only state authority — this process never switches phases). Stop reason and progress are
     * reported explicitly over ACP, never only to logs.
     */
    private void runResearchLoop(final SyncPromptContext ctx, String task) {
        com.aresstack.askai.research.runtime.loop.SolonToolInvoker browser =
                new com.aresstack.askai.research.runtime.loop.SolonToolInvoker(
                        environment.browserUrl, environment.browserTransport);
        com.aresstack.askai.research.runtime.loop.SolonToolInvoker research =
                new com.aresstack.askai.research.runtime.loop.SolonToolInvoker(
                        environment.researchUrl, environment.researchTransport);
        try {
            final com.aresstack.askai.research.runtime.loop.ResearchRunBudget budget =
                    com.aresstack.askai.research.runtime.loop.ResearchRunBudget.defaults();
            com.aresstack.askai.research.runtime.loop.ResearchLoop loop =
                    new com.aresstack.askai.research.runtime.loop.ResearchLoop(browser, research, budget,
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopClock() {
                                public long currentTimeMillis() {
                                    return System.currentTimeMillis();
                                }

                                public void sleepMillis(long millis) {
                                    try {
                                        Thread.sleep(millis);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            },
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopListener() {
                                public void status(String message) {
                                    // Diagnostics: technical details only — NEVER a chat bubble.
                                    ctx.sendMessage(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.log(message));
                                }

                                public void progress(
                                        com.aresstack.askai.research.runtime.loop.ResearchRunProgress p,
                                        com.aresstack.askai.research.runtime.loop.ResearchRunActivity activity) {
                                    // ONE in-place progress card per run, updated structurally.
                                    ctx.sendMessage(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.progress(p, budget, activity));
                                }

                                public void phaseReady(
                                        com.aresstack.askai.research.runtime.loop.ResearchStopReason reason) {
                                    // Event only — the HOST decides; carried in the run outcome + log.
                                    ctx.sendMessage(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.log("PHASE_READY: " + reason));
                                }

                                public void attention(String reason, String domainFamily, String url,
                                                      boolean resolved) {
                                    // Typed user-attention transition — rendered visibly by the UI.
                                    ctx.sendMessage(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.attention(reason, domainFamily, url, resolved));
                                }
                            }, cancelled, loadBrowserSearchSettings());
            // The MANDATORY local reranker was built and readiness-checked at session/new; inject it so
            // every organic candidate is scored before any web_open. (Non-browser turns never reach here.)
            if (reranker != null) {
                loop.setReranker(reranker);
            }
            // Inject the session's chosen initial-search strategy (null → keep the default browser path).
            if (searchStrategy != null) {
                loop.setSearchStrategy(searchStrategy);
            }
            // Continuation semantics: a later run of the same session never re-navigates target pages.
            loop.excludeVisited(visitedAcrossRuns);
            com.aresstack.askai.research.runtime.loop.ResearchStopReason reason = loop.run(task);
            visitedAcrossRuns.addAll(loop.getProgress().getVisitedCanonicalUrls());
            // The STRUCTURED outcome is the only basis for the user-facing result card.
            ctx.sendMessage(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .outcome(loop.outcome(reason)));
        } finally {
            browser.close();
            research.close();
        }
    }

    /**
     * The loop's settings come from the SAME config document the browser sidecar receives
     * ({@code ASKAI_BROWSER_SEARCH_CONFIG}); without one, exactly the central defaults apply. A broken
     * document fails the run visibly — settings are never silently substituted.
     */
    private com.aresstack.askai.browser.search.LegacyBrowserSearchSettings loadBrowserSearchSettings() {
        String path = environment.browserSearchConfigPath;
        if (path == null) {
            return com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument document =
                    com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument
                            .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.Decoded decoded =
                    com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec
                            .fromValues(document.values);
            if (!decoded.violations.isEmpty()) {
                throw new IllegalStateException("browser search config has invalid values:\n"
                        + new com.aresstack.askai.browser.search.SettingsValidationResult(
                                decoded.violations).describe());
            }
            return decoded.settings;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(
                    "Cannot read ASKAI_BROWSER_SEARCH_CONFIG=" + path + ": " + ex.getMessage());
        }
    }
}
