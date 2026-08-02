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
 * The deterministic autonomous research loop. It ORCHESTRATES only: every effect goes through MCP tools
 * ({@code web_*} on the browser endpoint, {@code source_accept}/{@code finding_add} on the research
 * endpoint) — never through stores directly. Decisions are CONTENT-driven (query terms against page
 * text/title and link text), not a scripted call sequence. Budgets are checked centrally in
 * {@link #beforeToolCall()} before every call; the stop reason is explicit and reported via the listener.
 * PHASE_READY is an event; the host stays the only state authority.
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
    /** Sites of the search engine(s) used this run — pure TRANSIT: never a page, host, source or link farm. */
    private final Set<String> searchProviderSites = new HashSet<String>();
    /** Domain families with a pending MANUAL challenge: locked (no navigation/retry) until resolved. */
    private final Set<String> challengedFamilies = new HashSet<String>();
    /** Frontier URLs deferred because their family is challenge-locked (QUEUED_DOMAIN_BLOCKED). */
    private final List<String> deferredUrls = new ArrayList<String>();
    /** Time spent waiting for the USER (manual challenge) — never counted against the time budget. */
    private long waitedForUserMillis;
    private long lastChallengeProbeAt;
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
     * at session start when the snapshot selects one. From {@code result.candidates} onward the loop behaves
     * identically no matter which strategy produced the URLs.
     */
    private com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy;
    /** The neutral per-run result count hint handed to the strategy (a provider may cap it further). */
    private static final int INITIAL_SEARCH_RESULT_COUNT = 10;
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

    /** Run the loop for a task; returns the explicit stop reason (also sent through the listener). */
    public ResearchStopReason run(String task) {
        Set<String> terms = queryTerms(task);
        ResearchStopReason reason = runInternal(terms);
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

    private ResearchStopReason runInternal(Set<String> terms) {
        // Seed: search, else nothing to do.
        List<String> frontier = new ArrayList<String>();
        ResearchStopReason seedStop = null;
        // How the INITIAL search concluded — kept so an empty frontier caused by a technical search failure
        // is reported as a technical problem, never as an honest "no relevant results".
        com.aresstack.askai.research.runtime.search.InitialSearchStatus initialStatus =
                com.aresstack.askai.research.runtime.search.InitialSearchStatus.NO_RESULTS;
        try {
            String query = join(terms);
            listener.progress(progress, apiProviderLabel == null
                    ? ResearchRunActivity.searching(query)
                    : ResearchRunActivity.searchingViaApi(query, apiProviderLabel));
            // Interchangeable INITIAL search: whether these candidates come from the browser SERP path or an
            // API provider, the code below (reranking → frontier → Playwright) is identical. URLs come
            // straight from typed SearchResultCandidates — no ATTEMPT:/CHALLENGE: text parsing.
            com.aresstack.askai.research.runtime.search.InitialSearchResult result = searchStrategy.search(
                    new com.aresstack.askai.research.runtime.search.InitialSearchRequest(
                            query, INITIAL_SEARCH_RESULT_COUNT, null, null),
                    cancellationSignal(),
                    new com.aresstack.askai.research.runtime.search.SearchBudgetGate() {
                        public boolean beforeToolCall() {
                            return ResearchLoop.this.beforeToolCall() == null;
                        }
                    });
            initialStatus = result.status;
            for (String providerHost : result.providerHosts) {
                searchProviderSites.add(familyOf(providerHost));
            }
            applyChallenges(result.challenges);
            // MANDATORY reranking BEFORE anything reaches the frontier: no page is ever opened in raw
            // engine order. Only the selected survivors, in relevance order, become frontier URLs; a
            // reranker failure ends the run with a typed reason and opens nothing.
            seedStop = seedReranking(query, result.candidates, frontier);
        } catch (ToolInvoker.EndpointUnavailable ex) {
            return ResearchStopReason.MCP_UNAVAILABLE;
        } catch (ToolInvoker.ToolFailure ex) {
            progress.error();
        } catch (RuntimeException ex) {
            // A malformed prepare/apply payload (codec DecodeException) must not crash the loop —
            // it is a tool-level failure; the run continues with an empty frontier (the error budget and
            // NO_RELEVANT_PATHS handle it as before).
            listener.status("web search preparation failed: " + ex.getMessage());
            progress.error();
        }
        if (seedStop != null) {
            return seedStop;
        }
        // Honest reporting: an INITIAL search that failed technically (SERP layout could not be extracted —
        // e.g. the layout-repair model was unavailable — or every engine was blocked with nothing
        // extractable) produced no candidates and therefore an empty frontier. That is NOT the same as
        // "no relevant results": surface it as a technical problem the user can retry.
        if (frontier.isEmpty()
                && initialStatus
                        == com.aresstack.askai.research.runtime.search.InitialSearchStatus.TECHNICAL_PROBLEM) {
            return ResearchStopReason.SEARCH_TECHNICAL_PROBLEM;
        }

        while (true) {
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            probeChallengesIfDue(frontier);
            if (frontier.isEmpty() && !deferredUrls.isEmpty() && !challengedFamilies.isEmpty()) {
                // WAITING_FOR_USER: only challenge-bound work is left. Wait cooperatively (short
                // cancel-aware ticks, ~1/s probes), without failing any navigation or time budget.
                ResearchStopReason wait = waitForManualChallenge(frontier);
                if (wait != null) {
                    return wait;
                }
                continue;
            }
            if (frontier.isEmpty()) {
                return sufficientOr(ResearchStopReason.NO_RELEVANT_PATHS);
            }
            String url = frontier.remove(0);
            String canonical = canonicalish(url);
            if (progress.alreadyVisited(canonical)) {
                continue; // already visited → never navigate again
            }
            if (challengedFamilies.contains(familyOf(url))) {
                deferredUrls.add(url); // QUEUED_DOMAIN_BLOCKED: starts only after the challenge resolves
                continue;
            }
            try {
                ResearchStopReason g2 = beforeToolCall();
                if (g2 != null) {
                    return g2;
                }
                String page = callBrowser("web_open", args("url", url));
                progress.success();
                // Host diversity MUST come from the FINAL post-redirect URL the browser actually landed on —
                // counting hostOf(requested) would count "bing.com" for every redirect link and make the
                // ≥2-hosts sufficiency threshold unreachable. Both addresses are marked visited; the page
                // and its host are counted once, under the final canonical URL.
                String finalUrl = finalUrlOf(page);
                String effectiveUrl = finalUrl == null || finalUrl.isEmpty() ? url : finalUrl;
                String finalCanonical = canonicalish(effectiveUrl);
                String finalHost = hostOf(effectiveUrl);
                String pageTitle = titleOf(page);
                if (isSearchProviderSite(finalHost)) {
                    // The search engine is TRANSIT (its verticals like /videos or /shopping ended up in
                    // the frontier): mark visited so it is never re-opened, but it counts as neither page
                    // nor host, is never a source, and its links are not harvested.
                    progress.noteVisitedAlias(finalCanonical);
                    progress.noteVisitedAlias(canonical);
                    listener.status("skipped search-provider page: " + effectiveUrl);
                    listener.progress(progress,
                            ResearchRunActivity.pageSkipped(effectiveUrl, finalHost, pageTitle));
                    continue;
                }
                progress.pageVisited(finalCanonical, finalHost);
                if (!finalCanonical.equals(canonical)) {
                    // A redirect: the requested address is marked visited too (but never counted).
                    progress.noteVisitedAlias(canonical);
                }
                listener.progress(progress, ResearchRunActivity.readingPage(effectiveUrl, finalHost, pageTitle));
                String captureId = field(page, "capture_id");
                String pageText = page.toLowerCase(Locale.ROOT);

                if (matches(pageText, terms)) {
                    ResearchStopReason g3 = acceptAndRecordFinding(captureId, page, terms,
                            effectiveUrl, finalHost, pageTitle);
                    if (g3 != null) {
                        return g3;
                    }
                } else {
                    listener.status("skipped irrelevant page: " + url);
                    listener.progress(progress, ResearchRunActivity.pageSkipped(effectiveUrl, finalHost, pageTitle));
                }

                // Follow only links whose text hints at the task (content-driven, not order-driven).
                ResearchStopReason g4 = beforeToolCall();
                if (g4 != null) {
                    return g4;
                }
                String links = callBrowser("web_links", args());
                progress.success();
                for (String line : links.split("\n")) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (matches(lower, terms)) {
                        String linkUrl = lastUrl(line);
                        if (linkUrl != null && !isSearchProviderSite(hostOf(linkUrl))
                                && !progress.alreadyVisited(canonicalish(linkUrl))) {
                            frontier.add(linkUrl);
                        }
                    }
                }
            } catch (ToolInvoker.EndpointUnavailable ex) {
                return ResearchStopReason.MCP_UNAVAILABLE;
            } catch (ToolInvoker.ToolFailure ex) {
                progress.error();
                listener.status("tool failed: " + ex.getMessage());
            }
        }
    }

    /**
     * The MANDATORY reranking gate between structured extraction and navigation. The call is budgeted
     * like any other tool (against the ResearchRunBudget, NOT the browser MCP) and honours cancellation.
     * On SUCCESS it fills {@code frontier} with the selected candidates in relevance order and returns
     * {@code null}; on any reranker failure (or NO_SEMANTIC_MATCHES) it opens nothing and returns the
     * typed stop reason — never a raw engine-order fallback. NO_CANDIDATES (an empty search) returns
     * {@code null} so the run ends as NO_RELEVANT_PATHS, not as a reranker failure.
     */
    private ResearchStopReason seedReranking(String query,
            List<com.aresstack.askai.browser.search.SearchResultCandidate> candidates,
            List<String> frontier) {
        if (candidates.isEmpty()) {
            return null; // nothing to rerank or open → NO_RELEVANT_PATHS via the main loop
        }
        ResearchStopReason gate = beforeToolCall();
        if (gate != null) {
            return gate; // budget/cancel before the very first tool call
        }
        com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult result =
                reranker.rerank(query, candidates, cancellationSignal());
        listener.status("reranking [" + result.modelName + "]: " + result.diagnostics);
        switch (result.outcome) {
            case SUCCESS:
                progress.success();
                for (com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate ranked
                        : result.selected) {
                    if (!ranked.candidate.resolvedTargetUrl.isEmpty()) {
                        frontier.add(ranked.candidate.resolvedTargetUrl);
                    }
                }
                return null;
            case NO_CANDIDATES:
                return null; // empty search, not a reranker failure
            case NO_SEMANTIC_MATCHES:
                return ResearchStopReason.NO_SEMANTIC_MATCHES;
            case TIMEOUT:
                progress.error();
                return ResearchStopReason.RERANKER_TIMEOUT;
            case INVALID_RESPONSE:
                progress.error();
                return ResearchStopReason.RERANKER_INVALID_RESPONSE;
            case CONFIGURATION_ERROR:
                progress.error();
                return ResearchStopReason.RERANKER_CONFIGURATION_ERROR;
            case CANCELLED:
                return ResearchStopReason.USER_CANCELLED;
            case BUDGET_EXHAUSTED:
                return sufficientOr(ResearchStopReason.TOOL_BUDGET_EXHAUSTED);
            case RERANKER_UNAVAILABLE:
            default:
                progress.error();
                return ResearchStopReason.RERANKER_UNAVAILABLE;
        }
    }

    /** Accept the capture and store one finding — via MCP only; duplicates are NOT errors. */
    private ResearchStopReason acceptAndRecordFinding(String captureId, String page, Set<String> terms,
                                                      String pageUrl, String pageHost, String pageTitle)
            throws ToolInvoker.EndpointUnavailable {
        if (captureId == null) {
            return null;
        }
        try {
            ResearchStopReason gate = beforeToolCall();
            if (gate != null) {
                return gate;
            }
            String accepted = sourceAcceptancePort.accept(captureId);
            progress.success();
            String sourceId = field(accepted, "source_id");
            boolean duplicate = accepted.contains("duplicate=true");
            if (sourceId == null || "-".equals(sourceId)) {
                return null;
            }
            if (accepted.contains("ALREADY_ACCEPTED")) {
                return null; // idempotent outcome, no new source, no repeated claim
            }
            progress.sourceAccepted();
            listener.status("accepted " + sourceId + (duplicate ? " (duplicate content)" : ""));
            listener.progress(progress, ResearchRunActivity.sourceAccepted(pageUrl, pageHost, pageTitle));
            // One finding per NEW claim; a duplicate source never repeats the same claim unchecked.
            String claim = "Evidence for [" + join(terms) + "] in \"" + field(page, "title") + "\"";
            if (!duplicate && claimedSourceIds.add(claim)) {
                ResearchStopReason g2 = beforeToolCall();
                if (g2 != null) {
                    return g2;
                }
                callResearch("finding_add", args("source_id", sourceId, "text", claim));
                progress.success();
            }
        } catch (ToolInvoker.ToolFailure ex) {
            // A rejected write (state changed under us) ends the run explicitly, not as a crash.
            if (ex.getMessage() != null && ex.getMessage().contains("Not allowed in the current state")) {
                return ex.getMessage().contains("waiting_approval")
                        ? ResearchStopReason.APPROVAL_REQUIRED : ResearchStopReason.STATE_CHANGED;
            }
            progress.error();
        }
        return null;
    }

    // ------------------------------------------------------------------ manual challenge cooperation

    private com.aresstack.askai.browser.search.inference.CancellationSignal cancellationSignal() {
        return new com.aresstack.askai.browser.search.inference.CancellationSignal() {
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    /** Apply the TYPED challenge states carried by a prepared search — no CHALLENGE: text parsing. */
    private void applyChallenges(
            List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges) {
        for (com.aresstack.askai.browser.search.repair.SearchChallengeState challenge : challenges) {
            String family = familyOf(challenge.family);
            if (!family.isEmpty() && challengedFamilies.add(family)) {
                listener.status("manual challenge pending on " + family);
                listener.attention("CAPTCHA", family, challenge.url, false);
            }
        }
    }

    /** Parse typed CHALLENGE/RESOLVED lines (from web_challenge_status) and apply them. */
    private void applyChallengeLines(String text) {
        for (String line : (text == null ? "" : text).split("\n")) {
            if (line.startsWith("CHALLENGE: ")) {
                String rest = line.substring("CHALLENGE: ".length()).trim();
                int space = rest.indexOf(' ');
                String family = familyOf(space < 0 ? rest : rest.substring(0, space));
                String url = space < 0 ? "" : rest.substring(space + 1).trim();
                if (!family.isEmpty() && challengedFamilies.add(family)) {
                    listener.status("manual challenge pending on " + family);
                    listener.attention("CAPTCHA", family, url, false);
                }
            } else if (line.startsWith("RESOLVED: ")) {
                unlockFamily(familyOf(line.substring("RESOLVED: ".length()).trim()));
            } else if (line.equals("NONE")) {
                // The browser has no pending challenge (it may have been consumed elsewhere): unlock all.
                for (String family : new ArrayList<String>(challengedFamilies)) {
                    unlockFamily(family);
                }
            }
        }
    }

    private void unlockFamily(String family) {
        if (challengedFamilies.remove(family)) {
            listener.status("manual challenge resolved on " + family);
            listener.attention("CAPTCHA", family, "", true);
        }
    }

    /** Probe the parked challenge at most once per second; unlocked work returns to the frontier. */
    private void probeChallengesIfDue(List<String> frontier) {
        if (challengedFamilies.isEmpty()) {
            return;
        }
        long now = clock.currentTimeMillis();
        if (now - lastChallengeProbeAt < challengeProbeIntervalMillis) {
            return;
        }
        lastChallengeProbeAt = now;
        try {
            // Deliberately WITHOUT the budget gate: polling the user's pending challenge must never
            // exhaust a tool budget or count as research work.
            applyChallengeLines(browser.call("web_challenge_status", args()));
        } catch (ToolInvoker.EndpointUnavailable | ToolInvoker.ToolFailure ignored) {
            // The probe is best-effort; the next tick retries.
        }
        if (!deferredUrls.isEmpty()) {
            // Re-queue everything whose family is unlocked again (still-locked URLs re-defer on pull).
            List<String> requeue = new ArrayList<String>();
            for (String url : deferredUrls) {
                if (!challengedFamilies.contains(familyOf(url))) {
                    requeue.add(url);
                }
            }
            deferredUrls.removeAll(requeue);
            frontier.addAll(requeue);
        }
    }

    /**
     * The cooperative wait while ONLY challenge-bound work remains: cancel stays immediate, the time
     * budget is compensated (waiting for the user is never a budget failure), the challenge is probed
     * about once per second, and the card shows WAITING_FOR_USER. Returns a stop reason or {@code null}
     * when the frontier has work again.
     */
    private ResearchStopReason waitForManualChallenge(List<String> frontier) {
        String family = challengedFamilies.isEmpty() ? "" : challengedFamilies.iterator().next();
        listener.progress(progress, ResearchRunActivity.waitingForUser(family, ""));
        while (frontier.isEmpty() && !deferredUrls.isEmpty() && !challengedFamilies.isEmpty()) {
            if (cancelled.get()) {
                return ResearchStopReason.USER_CANCELLED;
            }
            long tickStart = clock.currentTimeMillis();
            clock.sleepMillis(challengeProbeIntervalMillis);
            waitedForUserMillis += Math.max(0, clock.currentTimeMillis() - tickStart);
            probeChallengesIfDue(frontier);
        }
        return cancelled.get() ? ResearchStopReason.USER_CANCELLED : null;
    }

    // ------------------------------------------------------------------ central budget gate

    /** Checked before EVERY tool call — the single budget gate (no scattered ifs). */
    private ResearchStopReason beforeToolCall() {
        ResearchStopReason now = stopReasonNow();
        if (now != null) {
            return now;
        }
        progress.toolCall();
        return null;
    }

    private ResearchStopReason stopReasonNow() {
        if (cancelled.get()) {
            return ResearchStopReason.USER_CANCELLED;
        }
        if (progress.getToolCalls() >= budget.getMaxToolCalls()) {
            return sufficientOr(ResearchStopReason.TOOL_BUDGET_EXHAUSTED);
        }
        if (progress.getPagesVisited() >= budget.getMaxPagesVisited()) {
            return sufficientOr(ResearchStopReason.PAGE_BUDGET_EXHAUSTED);
        }
        if (progress.getAcceptedSources() >= budget.getMaxAcceptedSources()) {
            return ResearchStopReason.SOURCE_BUDGET_EXHAUSTED;
        }
        if (progress.getConsecutiveErrors() >= budget.getMaxConsecutiveErrors()) {
            return ResearchStopReason.ERROR_BUDGET_EXHAUSTED;
        }
        // Waiting for the USER (manual challenge) is never budgeted time.
        if (clock.currentTimeMillis() - startedAt - waitedForUserMillis >= budget.getMaxDurationMillis()) {
            return sufficientOr(ResearchStopReason.TIME_BUDGET_EXHAUSTED);
        }
        return null;
    }

    private ResearchStopReason sufficientOr(ResearchStopReason fallback) {
        boolean sufficient = progress.getAcceptedSources() >= budget.getMinimumAcceptedSources()
                && progress.getDistinctHosts().size() >= budget.getMinimumDistinctHosts();
        return sufficient ? ResearchStopReason.SUFFICIENT_EVIDENCE : fallback;
    }

    // ------------------------------------------------------------------ tool plumbing + parsing

    private String callBrowser(String tool, Map<String, Object> a)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return browser.call(tool, a);
    }

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

    static Set<String> queryTerms(String task) {
        Set<String> terms = new HashSet<String>();
        for (String word : (task == null ? "" : task).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() >= 3) {
                terms.add(word);
            }
        }
        return terms;
    }

    private static boolean matches(String lowerText, Set<String> terms) {
        for (String term : terms) {
            if (lowerText.contains(term)) {
                return true;
            }
        }
        return false;
    }

    static String field(String result, String key) {
        for (String token : result.split("[\\s\\n]+")) {
            if (token.startsWith(key + "=")) {
                return token.substring(key.length() + 1).replace("\"", "");
            }
        }
        // title="a b c" spans tokens; handle quoted form.
        int i = result.indexOf(key + "=\"");
        if (i >= 0) {
            int end = result.indexOf('"', i + key.length() + 2);
            if (end > 0) {
                return result.substring(i + key.length() + 2, end);
            }
        }
        return null;
    }

    static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"]+").matcher(text == null ? "" : text);
        while (m.find()) {
            urls.add(m.group());
        }
        return urls;
    }

    private static String lastUrl(String line) {
        List<String> urls = extractUrls(line);
        return urls.isEmpty() ? null : urls.get(urls.size() - 1);
    }

    static String canonicalish(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int frag = u.indexOf('#');
        if (frag >= 0) {
            u = u.substring(0, frag);
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /**
     * The FINAL post-navigation URL out of a {@code web_open} result. Both known result shapes start with
     * "URL: &lt;url&gt;" — the bridge appends {@code title="…" capture_id=…} on the same line, the raw sidecar
     * puts TITLE on the next line; in both cases the URL is the token right after the prefix.
     */
    static String finalUrlOf(String page) {
        if (page == null || !page.startsWith("URL: ")) {
            return null;
        }
        String rest = page.substring("URL: ".length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        String url = rest.substring(0, end).trim();
        return url.isEmpty() ? null : url;
    }

    /**
     * The page title out of a {@code web_open} result. The bridge appends {@code title="…"} on the URL line
     * (parsed as the full quoted value, not just the first word), the raw sidecar reports a "TITLE: …" line.
     */
    static String titleOf(String page) {
        if (page == null) {
            return "";
        }
        int i = page.indexOf("title=\"");
        if (i >= 0) {
            int end = page.indexOf('"', i + "title=\"".length());
            if (end > 0) {
                return page.substring(i + "title=\"".length(), end);
            }
        }
        for (String line : page.split("\n")) {
            if (line.startsWith("TITLE: ")) {
                return line.substring("TITLE: ".length()).trim();
            }
        }
        return "";
    }

    /** Remove typed status lines (PROVIDER/CHALLENGE/RESOLVED/NONE) so their URLs never enter the frontier. */
    static String stripStatusLines(String results) {
        StringBuilder sb = new StringBuilder();
        for (String line : (results == null ? "" : results).split("\n")) {
            if (line.startsWith("PROVIDER: ") || line.startsWith("CHALLENGE: ")
                    || line.startsWith("RESOLVED: ") || line.startsWith("ATTEMPT: ")
                    || line.equals("NONE")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** All {@code PROVIDER: <host>} lines of a {@code web_search} result (fallback engines add more). */
    static List<String> providerHostsOf(String results) {
        List<String> hosts = new ArrayList<String>();
        for (String line : (results == null ? "" : results).split("\n")) {
            if (line.startsWith("PROVIDER: ")) {
                hosts.add(line.substring("PROVIDER: ".length()).trim().toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    private boolean isSearchProviderSite(String host) {
        return !host.isEmpty() && searchProviderSites.contains(familyOf(host));
    }

    /** The public-suffix aware domain family ({@code news.bbc.co.uk} → {@code bbc.co.uk}). */
    private String familyOf(String urlOrHost) {
        return domainKeys.resolve(urlOrHost).getRegistrableDomain();
    }

    static String hostOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) {
            return "";
        }
        String rest = url.substring(i + 3);
        int slash = rest.indexOf('/');
        return (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    private static String join(Set<String> terms) {
        StringBuilder sb = new StringBuilder();
        for (String t : terms) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
