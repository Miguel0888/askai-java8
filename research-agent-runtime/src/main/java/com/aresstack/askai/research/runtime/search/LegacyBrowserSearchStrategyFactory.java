package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.search.analysis.SleepingRetryDelay;
import com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.research.runtime.loop.McpLayoutRepairClient;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.function.LongSupplier;

/**
 * Builds the default {@link LegacyBrowserSearchStrategy} — the unchanged browser SERP path over MCP. This
 * is the ONLY place in the runtime (besides the strategy itself) that knows about the layout-repair client,
 * so the {@link com.aresstack.askai.research.runtime.loop.ResearchLoop} depends only on the neutral
 * {@link SearchStrategy} seam and never on {@link McpLayoutRepairClient} directly.
 *
 * <p>Model-free by default: no {@code StructuredInferencePort} adapter is wired, so a low-confidence SERP
 * yields no results (honest) until the research-model runtime injects a real port at a higher layer.</p>
 */
public final class LegacyBrowserSearchStrategyFactory {

    private LegacyBrowserSearchStrategyFactory() {
    }

    public static SearchStrategy createDefault(ToolInvoker browser, LegacyBrowserSearchSettings settings,
                                               LongSupplier nowEpochMillis) {
        // No inference port wired: a low-confidence SERP stays honestly unresolvable (never fabricated).
        return createDefault(browser, settings, nowEpochMillis, new UnavailableStructuredInferencePort());
    }

    /**
     * As {@link #createDefault(ToolInvoker, LegacyBrowserSearchSettings, LongSupplier)} but with an explicit
     * structured-inference port for the model-backed SERP layout repair. AskAI publishes the port's endpoint
     * (the central main model); when none is available the caller passes {@code UnavailableStructuredInferencePort}.
     */
    public static SearchStrategy createDefault(ToolInvoker browser, LegacyBrowserSearchSettings settings,
                                               LongSupplier nowEpochMillis, StructuredInferencePort inferencePort) {
        McpLayoutRepairClient repairClient = new McpLayoutRepairClient(browser,
                new SearchLayoutRepairCoordinator(settings, inferencePort,
                        InferenceBudgetGate.ALLOW_ALL, new SleepingRetryDelay(), null));
        return new LegacyBrowserSearchStrategy(repairClient, nowEpochMillis);
    }
}
