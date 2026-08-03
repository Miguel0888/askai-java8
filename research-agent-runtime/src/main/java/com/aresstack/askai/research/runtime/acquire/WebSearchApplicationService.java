package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The deterministic web-acquisition engine, extracted VERBATIM from {@code ResearchLoop} (T2a): SearchStrategy
 * execution → mandatory reranking → frontier → redirect/canonical visited handling → search-provider transit
 * filtering → {@code web_open} → deterministic page relevance → {@link SourceAcceptancePort} → {@code web_links}
 * → link relevance/frontier expansion → challenge/deferred-domain handling → budgets → cancellation → terminal
 * acquisition stop reason. Every effect goes through MCP tools; decisions are content-driven.
 *
 * <p>It is caller-agnostic: the autonomous research loop and a user-triggered manual search both consume it.
 * It NEVER calls {@code finding_add}/{@code notes_append}, never signals PHASE_READY and never touches the
 * state machine or the model. When it accepts a source it notifies an {@link AcceptedSourceListener} AT THAT
 * POINT (before {@code web_links}) so the caller can do its own research-specific work (e.g. recording a
 * finding) without changing the observable {@code source_accept → finding_add → web_links} order.</p>
 */
public final class WebSearchApplicationService {

    /** A source the acquisition accepted, handed to the caller at the acceptance point (before web_links). */
    public static final class AcceptedSource {
        private final String sourceId;
        private final boolean duplicate;
        private final String captureId;
        private final String pageUrl;
        private final String pageHost;
        private final String pageTitle;
        private final String page;

        public AcceptedSource(String sourceId, boolean duplicate, String captureId, String pageUrl,
                              String pageHost, String pageTitle, String page) {
            this.sourceId = sourceId;
            this.duplicate = duplicate;
            this.captureId = captureId;
            this.pageUrl = pageUrl;
            this.pageHost = pageHost;
            this.pageTitle = pageTitle;
            this.page = page;
        }

        public String getSourceId() {
            return sourceId;
        }

        public boolean isDuplicate() {
            return duplicate;
        }

        public String getCaptureId() {
            return captureId;
        }

        public String getPageUrl() {
            return pageUrl;
        }

        public String getPageHost() {
            return pageHost;
        }

        public String getPageTitle() {
            return pageTitle;
        }

        public String getPage() {
            return page;
        }
    }

    /** The single central budget/cancel gate, exposed so the caller's per-source work is budgeted identically. */
    public interface ToolBudget {
        ResearchStopReason beforeToolCall();
    }

    /**
     * Notified for each NEWLY accepted source at the acquisition acceptance point (before {@code web_links}).
     * The caller may perform additional BUDGETED work through {@code budget} and return a stop reason to abort
     * the run, or {@code null} to continue. The service itself knows nothing about findings.
     */
    public interface AcceptedSourceListener {
        ResearchStopReason onAccepted(AcceptedSource source, ToolBudget budget)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
    }

    private static final int INITIAL_SEARCH_RESULT_COUNT = 10;

    private final ToolInvoker browser;
    private final ResearchRunBudget budget;
    private final ResearchRunProgress progress;
    private final ResearchLoopClock clock;
    private final ResearchLoopListener listener;
    private final AtomicBoolean cancelled;
    private final com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy;
    private final String apiProviderLabel;
    private final com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker;
    private final com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys;
    private final SourceAcceptancePort sourceAcceptancePort;
    private final long startedAt;
    private final long challengeProbeIntervalMillis;
    private final AcceptedSourceListener acceptedSourceListener;
    /** true → wait for the user to solve a challenge; false → skip the blocked page (leaves it parked). */
    private final boolean challengeWaitForUser;

    /** Sites of the search engine(s) used this run — pure TRANSIT: never a page, host, source or link farm. */
    private final Set<String> searchProviderSites = new HashSet<String>();
    /** Domain families with a pending MANUAL challenge: locked (no navigation/retry) until resolved. */
    private final Set<String> challengedFamilies = new HashSet<String>();
    /** Frontier URLs deferred because their family is challenge-locked (QUEUED_DOMAIN_BLOCKED). */
    private final List<String> deferredUrls = new ArrayList<String>();
    /** Time spent waiting for the USER (manual challenge) — never counted against the time budget. */
    private long waitedForUserMillis;
    private long lastChallengeProbeAt;

    private final ToolBudget budgetGate = new ToolBudget() {
        public ResearchStopReason beforeToolCall() {
            return WebSearchApplicationService.this.beforeToolCall();
        }
    };

    public WebSearchApplicationService(ToolInvoker browser, ResearchRunBudget budget,
                                       ResearchRunProgress progress, ResearchLoopClock clock,
                                       ResearchLoopListener listener, AtomicBoolean cancelled,
                                       com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy,
                                       String apiProviderLabel,
                                       com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker,
                                       com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
                                       SourceAcceptancePort sourceAcceptancePort, long startedAt,
                                       long challengeProbeIntervalMillis,
                                       AcceptedSourceListener acceptedSourceListener,
                                       boolean challengeWaitForUser) {
        this.browser = browser;
        this.budget = budget;
        this.progress = progress;
        this.clock = clock;
        this.listener = listener;
        this.cancelled = cancelled;
        this.searchStrategy = searchStrategy;
        this.apiProviderLabel = apiProviderLabel;
        this.reranker = reranker;
        this.domainKeys = domainKeys;
        this.sourceAcceptancePort = sourceAcceptancePort;
        this.startedAt = startedAt;
        this.challengeProbeIntervalMillis = challengeProbeIntervalMillis;
        this.acceptedSourceListener = acceptedSourceListener;
        this.challengeWaitForUser = challengeWaitForUser;
    }

    /** Execute the deterministic acquisition for {@code terms}; returns the explicit acquisition stop reason. */
    public ResearchStopReason execute(Set<String> terms) {
        // Seed: search, else nothing to do.
        List<String> frontier = new ArrayList<String>();
        ResearchStopReason seedStop = null;
        // How the INITIAL search concluded — kept so an empty frontier caused by a technical search failure
        // is reported as a technical problem, never as an honest "no relevant results".
        com.aresstack.askai.research.runtime.search.InitialSearchStatus initialStatus =
                com.aresstack.askai.research.runtime.search.InitialSearchStatus.NO_RESULTS;
        try {
            String query = WebAcquisitionText.join(terms);
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
                            return WebSearchApplicationService.this.beforeToolCall() == null;
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
            String canonical = WebAcquisitionText.canonicalish(url);
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
                String finalUrl = WebAcquisitionText.finalUrlOf(page);
                String effectiveUrl = finalUrl == null || finalUrl.isEmpty() ? url : finalUrl;
                String finalCanonical = WebAcquisitionText.canonicalish(effectiveUrl);
                String finalHost = WebAcquisitionText.hostOf(effectiveUrl);
                String pageTitle = WebAcquisitionText.titleOf(page);
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
                String captureId = WebAcquisitionText.field(page, "capture_id");
                String pageText = page.toLowerCase(Locale.ROOT);

                if (WebAcquisitionText.matches(pageText, terms)) {
                    ResearchStopReason g3 = acceptSource(captureId, page, effectiveUrl, finalHost, pageTitle);
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
                    if (WebAcquisitionText.matches(lower, terms)) {
                        String linkUrl = WebAcquisitionText.lastUrl(line);
                        if (linkUrl != null && !isSearchProviderSite(WebAcquisitionText.hostOf(linkUrl))
                                && !progress.alreadyVisited(WebAcquisitionText.canonicalish(linkUrl))) {
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
                        // Park the candidate with its reranker score BEFORE it is visited, so every hit is
                        // in the store immediately (score visible) and its full text is filled only on a
                        // successful visit. Best-effort: a park failure never aborts the search.
                        parkCandidate(ranked);
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

    /**
     * Park a single reranked candidate (best-effort). Parking is host-side bookkeeping, so it is deliberately
     * NOT gated by the tool budget and a failure is logged and swallowed — the search continues regardless.
     */
    private void parkCandidate(com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate ranked) {
        try {
            sourceAcceptancePort.park(ranked.candidate.resolvedTargetUrl, ranked.candidate.title,
                    ranked.candidate.snippet, ranked.score);
        } catch (ToolInvoker.ToolFailure ex) {
            listener.status("park failed: " + ex.getMessage());
        } catch (ToolInvoker.EndpointUnavailable ex) {
            listener.status("park skipped (endpoint unavailable)");
        }
    }

    /**
     * Accept the capture as a source (via {@link SourceAcceptancePort}) and, for a NEW source, notify the
     * caller AT THIS POINT (before web_links) so it can do its own budgeted per-source work. Duplicates are
     * NOT errors. A rejected write (state changed under us) ends the run explicitly, not as a crash.
     */
    private ResearchStopReason acceptSource(String captureId, String page, String pageUrl, String pageHost,
                                            String pageTitle)
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
            String sourceId = WebAcquisitionText.field(accepted, "source_id");
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
            // Research-specific per-source work (e.g. finding_add) happens in the caller's listener at THIS
            // exact point, budgeted via budgetGate, so source_accept → (finding_add) → web_links is preserved.
            return acceptedSourceListener.onAccepted(
                    new AcceptedSource(sourceId, duplicate, captureId, pageUrl, pageHost, pageTitle, page),
                    budgetGate);
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
        if (!challengeWaitForUser) {
            // Uniform "skip on challenge" choice: do NOT wait for the user. The challenge-blocked URLs stay
            // deferred and are simply left behind (their parked sources keep an empty full text).
            listener.status("skipping challenge-blocked pages (wait-for-user disabled): " + family);
            return sufficientOr(ResearchStopReason.NO_RELEVANT_PATHS);
        }
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

    // ------------------------------------------------------------------ tool plumbing

    private String callBrowser(String tool, Map<String, Object> a)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return browser.call(tool, a);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private boolean isSearchProviderSite(String host) {
        return !host.isEmpty() && searchProviderSites.contains(familyOf(host));
    }

    /** The public-suffix aware domain family ({@code news.bbc.co.uk} → {@code bbc.co.uk}). */
    private String familyOf(String urlOrHost) {
        return domainKeys.resolve(urlOrHost).getRegistrableDomain();
    }
}
