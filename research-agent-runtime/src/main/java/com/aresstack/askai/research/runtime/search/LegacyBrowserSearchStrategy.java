package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.McpLayoutRepairClient;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.function.LongSupplier;

/**
 * The legacy strategy: it delegates VERBATIM to the existing browser SERP path over MCP
 * ({@code web_search_prepare} / {@code web_search_apply_layout} via {@link McpLayoutRepairClient}). No SERP
 * parsing, layout repair, challenge handling or transit-host logic is reimplemented here — this class only
 * adapts the neutral {@link SearchStrategy} contract onto the unchanged repair client, so the browser-based
 * Bing/Google/DuckDuckGo discovery remains available exactly as before, now as one explicit strategy.
 */
public final class LegacyBrowserSearchStrategy implements SearchStrategy {

    private final McpLayoutRepairClient repairClient;
    private final LongSupplier nowEpochMillis;

    public LegacyBrowserSearchStrategy(McpLayoutRepairClient repairClient, LongSupplier nowEpochMillis) {
        if (repairClient == null) {
            throw new IllegalArgumentException("repairClient must not be null");
        }
        if (nowEpochMillis == null) {
            throw new IllegalArgumentException("nowEpochMillis must not be null");
        }
        this.repairClient = repairClient;
        this.nowEpochMillis = nowEpochMillis;
    }

    @Override
    public InitialSearchResult search(InitialSearchRequest request, final CancellationSignal cancellation,
                                      final SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        // nowEpochMillis feeds only the layout-repair coordinator's clock-aware retries; it comes from the
        // loop's injected clock so scripted-clock tests stay faithful.
        McpLayoutRepairClient.Result result = repairClient.searchWithRepair(
                request.getQuery(), cancellation, nowEpochMillis.getAsLong(),
                new McpLayoutRepairClient.ToolBudget() {
                    public boolean beforeToolCall() {
                        return budget.beforeToolCall();
                    }
                });
        return new InitialSearchResult(result.candidates, result.providerHosts, result.challenges,
                result.diagnostics);
    }
}
