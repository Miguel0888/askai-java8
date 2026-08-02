package com.aresstack.askai.research.runtime.loop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The deterministic autonomous research loop. It now DELEGATES the web-acquisition engine (SearchStrategy →
 * rerank → frontier → browse → capture → source acceptance → links → budgets/cancel/CAPTCHA/cleanup) to
 * {@link com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService}, and keeps ONLY the
 * research-specific concerns: deriving the query terms, recording a finding ({@code finding_add}) for each
 * newly accepted source, the run summary, and the PHASE_READY event. The host stays the only state authority.
 * A user-triggered manual search reuses the SAME acquisition service but records no findings.
 */
public final class ResearchLoop {

    private final ToolInvoker browser;
    /** Kept so the productive structured-inference port can be woven into the default browser strategy. */
    private final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings;
    private final ToolInvoker research;
    private final ResearchRunBudget budget;
    private final ResearchRunProgress progress = new ResearchRunProgress();
    private final ResearchLoopClock clock;
    private final ResearchLoopListener listener;
    private final AtomicBoolean cancelled;
    private final long startedAt;
    private final Set<String> claimedSourceIds = new HashSet<String>();
    /** From the CAPTCHA settings (single default origin: LegacyBrowserSearchDefaults). */
    private final long challengeProbeIntervalMillis;
    /** Public-suffix aware domain families; tests may inject a fake (e.g. host:port for local worlds). */
    private com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys =
            new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver();
    /**
     * The interchangeable INITIAL-search seam — the ONLY way the loop obtains its start URLs. It defaults to
     * the browser SERP path built by {@link
     * com.aresstack.askai.research.runtime.search.LegacyBrowserSearchStrategyFactory} (so the loop itself
     * knows nothing about the layout-repair client); the productive runtime injects an API-provider strategy
     * at session start when the snapshot selects one. From {@code result.candidates} onward the acquisition
     * behaves identically no matter which strategy produced the URLs.
     */
    private com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy;
    /**
     * The MANDATORY local cross-encoder reranking step. Every organic candidate is reranked and only the
     * selected survivors — in relevance order — ever reach the frontier and {@code web_open}; a reranker
     * failure opens NOTHING and ends the run with a typed reranker stop reason (never a raw-order
     * fallback). The productive runtime injects the HTTP-backed reranker at session start; the default is
     * the EXPLICITLY named {@link com.aresstack.askai.research.runtime.rerank.EngineOrderReranker} test
     * adapter, so the loop never infers "engine order" from the ABSENCE of a reranker — production fails
     * earlier, at session start, when the mandatory snapshot is missing.
     */
    private com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker =
            new com.aresstack.askai.research.runtime.rerank.EngineOrderReranker();
    /**
     * The source-acceptance seam. Defaults to the agent's phase-gated {@code source_accept} tool (the exact
     * inline behavior this loop always had); a user-triggered search later injects the internal
     * {@code manual_source_accept} port instead — same acquisition code, different authorization/origin.
     */
    private com.aresstack.askai.research.runtime.acquire.SourceAcceptancePort sourceAcceptancePort;

    /** Inject the source-acceptance route (T2c wires the manual port; default stays the agent's tool). */
    public void setSourceAcceptancePort(
            com.aresstack.askai.research.runtime.acquire.SourceAcceptancePort port) {
        if (port != null) {
            this.sourceAcceptancePort = port;
        }
    }

    /** Inject the mandatory reranker (productive runtime, and reranking integration tests). */
    public void setReranker(com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker) {
        if (reranker != null) {
            this.reranker = reranker;
        }
    }

    /** Inject a different domain-key resolver (tests/dev only; production keeps the public-suffix one). */
    public void setDomainKeyResolver(com.aresstack.askai.browser.domain.DomainKeyResolver resolver) {
        if (resolver != null) {
            this.domainKeys = resolver;
        }
    }

    /**
     * Inject an initial-search strategy (productive runtime selects the API-provider strategy; tests inject
     * fakes, incl. a legacy-browser strategy over a scripted repair client). Passing {@code null} keeps the
     * current strategy — there is never a silent fallback.
     */
    public void setSearchStrategy(com.aresstack.askai.research.runtime.search.SearchStrategy strategy) {
        setSearchStrategy(strategy, null);
    }

    /**
     * @param apiProviderLabel the user-facing REST provider name ("DataForSEO", …) when the strategy is an
     *                         API provider — the search progress then says so (and shows NO browser), or
     *                         {@code null} for the browser SERP path.
     */
    public void setSearchStrategy(com.aresstack.askai.research.runtime.search.SearchStrategy strategy,
                                  String apiProviderLabel) {
        if (strategy != null) {
            this.searchStrategy = strategy;
            this.apiProviderLabel = apiProviderLabel;
        }
    }

    /** Non-null exactly when the injected strategy is a REST provider (no browser during the search). */
    private String apiProviderLabel;

    /**
     * Weave the productive structured-inference port (the central AskAI main model, published by the host)
     * into the DEFAULT browser SERP strategy so a low-confidence layout can be model-repaired instead of
     * yielding zero candidates. Only meaningful on the browser path — a non-browser API-provider strategy
     * (injected via {@link #setSearchStrategy}) does not use it. Passing {@code null} keeps the current
     * strategy (the honest unavailable-fallback).
     */
    public void setStructuredInferencePort(
            com.aresstack.askai.browser.search.inference.StructuredInferencePort port) {
        if (port != null) {
            this.searchStrategy = com.aresstack.askai.research.runtime.search
                    .LegacyBrowserSearchStrategyFactory.createDefault(browser, searchSettings,
                            new java.util.function.LongSupplier() {
                                public long getAsLong() {
                                    return clock.currentTimeMillis();
                                }
                            }, port);
        }
    }

    public ResearchLoop(ToolInvoker browser, ToolInvoker research, ResearchRunBudget budget,
                        ResearchLoopClock clock, ResearchLoopListener listener, AtomicBoolean cancelled) {
        this(browser, research, budget, clock, listener, cancelled,
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
    }

    public ResearchLoop(ToolInvoker browser, ToolInvoker research, ResearchRunBudget budget,
                        ResearchLoopClock clock, ResearchLoopListener listener, AtomicBoolean cancelled,
                        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings) {
        this.browser = browser;
        this.research = research;
        this.sourceAcceptancePort =
                new com.aresstack.askai.research.runtime.acquire.AgentSourceAcceptancePort(research);
        this.budget = budget;
        this.clock = clock;
        this.listener = listener;
        this.cancelled = cancelled;
        this.searchSettings = searchSettings;
        this.challengeProbeIntervalMillis = searchSettings.captcha.challengeProbeIntervalMillis;
        this.startedAt = clock.currentTimeMillis();
        // Default seam: the unchanged browser SERP path. The factory owns the layout-repair client so the
        // loop depends only on the neutral SearchStrategy — never on McpLayoutRepairClient directly.
        this.searchStrategy = com.aresstack.askai.research.runtime.search
                .LegacyBrowserSearchStrategyFactory.createDefault(browser, searchSettings,
                        new java.util.function.LongSupplier() {
                            public long getAsLong() {
                                return clock.currentTimeMillis();
                            }
                        });
    }

    public ResearchRunProgress getProgress() {
        return progress;
    }

    public ResearchRunBudget getBudget() {
        return budget;
    }

    /**
     * Seed already-visited canonical URLs from earlier runs of the SAME research question (continuation with
     * a fresh budget): none of them is navigated again, and none of them counts as a page of this run.
     */
    public void excludeVisited(java.util.Collection<String> canonicalUrls) {
        if (canonicalUrls != null) {
            for (String canonical : canonicalUrls) {
                progress.noteVisitedAlias(canonical);
            }
        }
    }

    /**
     * Run for a task: derive the research terms, delegate the deterministic acquisition to the
     * {@link com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService} (recording a finding
     * for each newly accepted source at the acceptance point, so {@code source_accept → finding_add →
     * web_links} order is preserved), then emit the run summary and — for SUFFICIENT_EVIDENCE — PHASE_READY.
     */
    public ResearchStopReason run(String task) {
        final Set<String> terms = queryTerms(task);
        com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService acquisition =
                new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService(
                        browser, budget, progress, clock, listener, cancelled, searchStrategy, apiProviderLabel,
                        reranker, domainKeys, sourceAcceptancePort, startedAt, challengeProbeIntervalMillis,
                        new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                .AcceptedSourceListener() {
                            public ResearchStopReason onAccepted(
                                    com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                            .AcceptedSource source,
                                    com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                            .ToolBudget budgetGate)
                                    throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
                                return recordFinding(source.getSourceId(), source.isDuplicate(),
                                        source.getPage(), terms, budgetGate);
                            }
                        });
        ResearchStopReason reason = acquisition.execute(terms);
        listener.status("run stopped: " + reason
                + " (pages=" + progress.getPagesVisited()
                + " sources=" + progress.getAcceptedSources()
                + " hosts=" + progress.getDistinctHosts().size() + ")");
        if (reason == ResearchStopReason.SUFFICIENT_EVIDENCE) {
            listener.phaseReady(reason); // an EVENT — the host decides about WAITING_APPROVAL
        }
        return reason;
    }

    /** The structured, user-facing result of this run (built from the final progress vs. the budget). */
    public ResearchRunOutcome outcome(ResearchStopReason reason) {
        return ResearchRunOutcome.from(reason, progress, budget);
    }

    /**
     * RESEARCH-SPECIFIC finding recording (NOT part of the shared web-acquisition service): one finding per
     * NEW claim; a duplicate source never repeats the same claim, and the {@code finding_add} write is budgeted
     * exactly like before through the acquisition's own gate. Invoked by the acquisition service as its
     * accepted-source listener, at the acceptance point (before web_links), so a user-triggered manual search
     * can reuse the acquisition but record no agent findings.
     */
    private ResearchStopReason recordFinding(String sourceId, boolean duplicate, String page, Set<String> terms,
            com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService.ToolBudget budgetGate)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        String claim = "Evidence for [" + join(terms) + "] in \"" + field(page, "title") + "\"";
        if (!duplicate && claimedSourceIds.add(claim)) {
            ResearchStopReason g2 = budgetGate.beforeToolCall();
            if (g2 != null) {
                return g2;
            }
            callResearch("finding_add", args("source_id", sourceId, "text", claim));
            progress.success();
        }
        return null;
    }

    // ------------------------------------------------------------------ research tool plumbing

    private String callResearch(String tool, Map<String, Object> a)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return research.call(tool, a);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ pure text helpers (see WebAcquisitionText)
    // Thin delegators kept so existing callers and tests remain stable after the helper extraction (T2a Step 2).

    static Set<String> queryTerms(String task) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.queryTerms(task);
    }

    static String field(String result, String key) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.field(result, key);
    }

    static List<String> extractUrls(String text) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.extractUrls(text);
    }

    static String canonicalish(String url) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.canonicalish(url);
    }

    static String finalUrlOf(String page) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.finalUrlOf(page);
    }

    static String titleOf(String page) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.titleOf(page);
    }

    static String stripStatusLines(String results) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.stripStatusLines(results);
    }

    static List<String> providerHostsOf(String results) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.providerHostsOf(results);
    }

    static String hostOf(String url) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.hostOf(url);
    }

    private static String join(Set<String> terms) {
        return com.aresstack.askai.research.runtime.acquire.WebAcquisitionText.join(terms);
    }
}
