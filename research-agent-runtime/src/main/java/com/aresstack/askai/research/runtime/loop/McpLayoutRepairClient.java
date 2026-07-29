package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordination;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The research-runtime driver of the two-step SERP layout repair over MCP. It calls
 * {@code web_search_prepare}; on REPAIR_REQUIRED it runs the model-using
 * {@link SearchLayoutRepairCoordinator} (profile or {@code StructuredInferencePort}) for each ordered
 * repair ticket and sends a validated decision back through {@code web_search_apply_layout} until one
 * yields organic results. If every repair fails, the outcome is EXTRACTION_FAILED — never a fabricated
 * empty engine. The central research tool budget is consulted BEFORE every MCP call via
 * {@link ToolBudget}; the coordinator's inference budget covers only the model calls.
 */
public final class McpLayoutRepairClient {

    /** The research runtime's central tool budget, checked before EACH MCP call. */
    public interface ToolBudget {
        boolean beforeToolCall();
    }

    /** The typed status the loop routes on — never a free-text string. */
    public enum Outcome {
        ORGANIC_RESULTS,
        NO_ORGANIC_RESULTS,
        CHALLENGE_PENDING,
        EXTRACTION_FAILED,
        BUDGET_EXHAUSTED,
        CANCELLED
    }

    /** The typed outcome the loop routes on, carrying transit hosts and challenges typed. */
    public static final class Result {
        public final Outcome status;
        public final List<SearchResultCandidate> candidates;
        public final List<String> providerHosts;
        public final List<SearchChallengeState> challenges;
        public final List<String> diagnostics;

        Result(Outcome status, List<SearchResultCandidate> candidates, List<String> providerHosts,
               List<SearchChallengeState> challenges, List<String> diagnostics) {
            this.status = status;
            this.candidates = Collections.unmodifiableList(candidates);
            this.providerHosts = Collections.unmodifiableList(providerHosts);
            this.challenges = Collections.unmodifiableList(challenges);
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }
    }

    private final ToolInvoker browser;
    private final SearchLayoutRepairCoordinator coordinator;

    public McpLayoutRepairClient(ToolInvoker browser, SearchLayoutRepairCoordinator coordinator) {
        this.browser = browser;
        this.coordinator = coordinator;
    }

    public Result searchWithRepair(String query, CancellationSignal cancellationSignal,
                                   long nowEpochMillis, ToolBudget budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        List<String> diagnostics = new ArrayList<String>();
        List<String> providerHosts = new ArrayList<String>();
        List<SearchChallengeState> challenges = new ArrayList<SearchChallengeState>();
        if (!budget.beforeToolCall()) {
            return result(Outcome.BUDGET_EXHAUSTED, noCandidates(), providerHosts, challenges,
                    diagnostics);
        }
        PreparedWebSearchResult prepared = SearchLayoutRepairJson.decodePrepared(
                browser.call("web_search_prepare", singletonArg("query", query)));
        diagnostics.addAll(prepared.diagnostics);
        providerHosts.addAll(prepared.providerHosts);
        challenges.addAll(prepared.challenges);

        switch (prepared.status) {
            case ORGANIC_RESULTS:
                return result(Outcome.ORGANIC_RESULTS, prepared.candidates, providerHosts, challenges,
                        diagnostics);
            case NO_ORGANIC_RESULTS:
                return result(Outcome.NO_ORGANIC_RESULTS, noCandidates(), providerHosts, challenges,
                        diagnostics);
            case CHALLENGE_PENDING:
                return result(Outcome.CHALLENGE_PENDING, noCandidates(), providerHosts, challenges,
                        diagnostics);
            case FAILED:
                return result(Outcome.EXTRACTION_FAILED, noCandidates(), providerHosts, challenges,
                        diagnostics);
            case REPAIR_REQUIRED:
            default:
                break;
        }

        for (SearchLayoutRepairRequest ticket : prepared.repairRequests) {
            if (cancellationSignal.isCancelled()) {
                return result(Outcome.CANCELLED, noCandidates(), providerHosts, challenges,
                        diagnostics);
            }
            SearchLayoutRepairCoordination coordination =
                    coordinator.coordinate(ticket, cancellationSignal, nowEpochMillis);
            diagnostics.addAll(coordination.diagnostics);
            if (!coordination.shouldSubmit()) {
                continue; // AI disabled/unavailable/validation-failed for this engine — try the next
            }
            if (!budget.beforeToolCall()) {
                return result(Outcome.BUDGET_EXHAUSTED, noCandidates(), providerHosts, challenges,
                        diagnostics);
            }
            SearchLayoutRepairResult applied = SearchLayoutRepairJson.decodeRepairResult(
                    browser.call("web_search_apply_layout",
                            singletonArg("submission",
                                    SearchLayoutRepairJson.encodeSubmission(coordination.submission))));
            diagnostics.addAll(applied.diagnostics);
            if (applied.isOrganic()) {
                return result(Outcome.ORGANIC_RESULTS, applied.candidates, providerHosts, challenges,
                        diagnostics);
            }
            // this ticket produced no valid block — try the next repair ticket / engine
        }
        return result(Outcome.EXTRACTION_FAILED, noCandidates(), providerHosts, challenges,
                diagnostics);
    }

    private static Result result(Outcome status, List<SearchResultCandidate> candidates,
                                 List<String> providerHosts, List<SearchChallengeState> challenges,
                                 List<String> diagnostics) {
        return new Result(status, candidates, providerHosts, challenges, diagnostics);
    }

    private static List<SearchResultCandidate> noCandidates() {
        return new ArrayList<SearchResultCandidate>();
    }

    private static Map<String, Object> singletonArg(String key, String value) {
        return Collections.<String, Object>singletonMap(key, value);
    }
}
