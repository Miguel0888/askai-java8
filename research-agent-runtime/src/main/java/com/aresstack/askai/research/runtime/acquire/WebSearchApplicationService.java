package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import com.aresstack.askai.browser.hud.ResearchHudCommand;
import com.aresstack.askai.browser.hud.ResearchHudState;

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
    /** Max scan→handle→re-scan cycles to make a concrete page readable; 0 disables the loop (read at once). */
    private final int maxReadinessRetries;
    /** Minimum body text length for a page to count as readable in the readiness probe. */
    private final int minReadableChars;
    /** The readiness verdict seam (default heuristic; a model-backed judge can be set for user searches). */
    private PageReadinessJudge readinessJudge;

    /** Sites of the search engine(s) used this run — pure TRANSIT: never a page, host, source or link farm. */
    private final Set<String> searchProviderSites = new HashSet<String>();
    /** Domain families with a pending MANUAL challenge: locked (no navigation/retry) until resolved. */
    private final Set<String> challengedFamilies = new HashSet<String>();
    /** Domain families that returned a TERMINAL access block this run: skipped for good (never retried). */
    private final Set<String> blockedFamilies = new HashSet<String>();
    /** Frontier URLs deferred because their family is challenge-locked (QUEUED_DOMAIN_BLOCKED). */
    private final List<String> deferredUrls = new ArrayList<String>();
    /** Time spent waiting for the USER (manual challenge) — never counted against the time budget. */
    private long waitedForUserMillis;
    private long lastChallengeProbeAt;
    /** Research HUD: the user paused autonomous navigation (Pause button); resumed via the overlay. */
    private volatile boolean hudPaused;
    /** Research HUD: user-set inter-page delay (Delay slider), applied before opening each page; NOT budgeted. */
    private volatile long hudDelayMillis;
    /** Upper bound for the inter-page delay slider (mirrors the overlay's max), so a stray value cannot stall a run. */
    private static final long HUD_MAX_DELAY_MILLIS = 30_000L;
    /**
     * Set when a browser call reports the endpoint is gone (the browser was closed / the sidecar died even after
     * one restart). It makes {@link #stopReasonNow} return MCP_UNAVAILABLE so the run ends TECHNICALLY instead of
     * spinning forever on a dead browser (→ manualSearchFailed → the composer is freed + a red error shows).
     */
    private volatile boolean browserGone;
    /** One-shot: a Skip/Next just fired, so the NEXT inter-page delay is bypassed (don't make the user wait again). */
    private volatile boolean skipNextInterPageDelay;
    /** Soft cap for an in-page user-wait (consent/challenge) so a stuck wait self-parks; Skip is the immediate escape. */
    private static final long HUD_USER_WAIT_TIMEOUT_MILLIS = 180_000L;
    /**
     * Research HUD master switch. Default ON; {@code -Daskai.research.hud.enabled=false} forces the no-op path
     * so the browser can be A/B tested with the overlay injection entirely off. If the run is stable only with
     * the HUD disabled, the fault is in the overlay/binding; if it dies either way, it is a general browser
     * regression. When off, {@link #renderHud} / {@link #pollHudCommands} never touch the browser.
     */
    private final boolean hudEnabled =
            !"false".equalsIgnoreCase(System.getProperty("askai.research.hud.enabled", "true"));
    /** Throttles identical, per-tick HUD failure logs (render/poll run every probe tick during a user-wait). */
    private String lastHudErrorLine;

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
                                       boolean challengeWaitForUser,
                                       int maxReadinessRetries, int minReadableChars) {
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
        this.maxReadinessRetries = maxReadinessRetries;
        this.minReadableChars = minReadableChars;
        this.readinessJudge = new HeuristicPageReadinessJudge(minReadableChars);
    }

    /** Replace the default heuristic readiness judge (e.g. a model-backed one for a user search). No-op on null. */
    public void setReadinessJudge(PageReadinessJudge judge) {
        if (judge != null) {
            this.readinessJudge = judge;
        }
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
            // The browser MCP endpoint is gone (the sidecar likely died): this is the concrete, retryable
            // technical cause behind a SEARCH_TECHNICAL_PROBLEM — log it, never swallow it.
            listener.status("[web-search] technical failure stage=SEED_SEARCH (endpoint unavailable — sidecar"
                    + " may be dead) cause=" + describe(ex));
            return ResearchStopReason.MCP_UNAVAILABLE;
        } catch (ToolInvoker.ToolFailure ex) {
            listener.status("[web-search] technical failure stage=SEED_SEARCH cause=" + describe(ex));
            progress.error();
        } catch (RuntimeException ex) {
            // A malformed prepare/apply payload (codec DecodeException) must not crash the loop —
            // it is a tool-level failure; the run continues with an empty frontier (the error budget and
            // NO_RELEVANT_PATHS handle it as before).
            listener.status("[web-search] technical failure stage=SEED_SEARCH_PREPARE cause=" + describe(ex));
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
            // HUD: apply Pause/Resume, and if paused, wait (cancel-aware) instead of opening the next page.
            applyHudPauseResume();
            ResearchStopReason pausedStop = awaitResumeIfPaused();
            if (pausedStop != null) {
                return pausedStop;
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
            if (blockedFamilies.contains(familyOf(url))) {
                // A terminal access block already hit this domain this run: never re-open it (unlike a
                // pending challenge, there is nothing to resolve).
                progress.noteVisitedAlias(canonical);
                listener.status("skipped blocked domain: " + url);
                continue;
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
                // HUD: honour the user-set inter-page delay before opening this page (Next/Skip/cancel interrupt).
                ResearchStopReason delayed = interPageDelay();
                if (delayed != null) {
                    return delayed;
                }
                String page = openWithReadiness(url);
                if (page == null) {
                    // The page could not be made readable (CAPTCHA skipped / consent not clearable / too
                    // little text): mark it visited so it is never retried, and leave its parked source with
                    // an empty full text. The score already tells the user this hit still needs reading.
                    progress.noteVisitedAlias(canonical);
                    listener.status("left parked (not readable): " + url);
                    continue;
                }
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
                listener.status("[web-search] technical failure stage=PAGE_OPEN (endpoint unavailable — sidecar"
                        + " may be dead) url=" + url + " cause=" + describe(ex));
                return ResearchStopReason.MCP_UNAVAILABLE;
            } catch (ToolInvoker.ToolFailure ex) {
                progress.error();
                listener.status("[web-search] technical failure stage=PAGE_OPEN url=" + url
                        + " cause=" + describe(ex));
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
     * The two-step "scan then read" visit of a CONCRETE page. Step 1: PROBE the page (web_probe) and, while it
     * is not readable, handle the obstruction — dismiss a consent banner (auto first; if that fails, tell the
     * user where to click and, when configured, wait), or wait for the user to solve a CAPTCHA (unless
     * wait-for-user is off, then skip) — re-probing between attempts, bounded by {@code maxReadinessRetries}
     * consent cycles. Step 2: only once readable, READ the full page (web_read, which assigns the capture id).
     * Returns the read page string (as {@code web_open} did), or {@code null} when the page could not be made
     * readable (its parked source then keeps an empty full text). With {@code maxReadinessRetries <= 0} it
     * degrades to the original single-step {@code web_open}. {@link #isReadable} is the seam an LLM judge can
     * replace.
     */
    private String openWithReadiness(String url)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        if (maxReadinessRetries <= 0) {
            return callBrowser("web_open", args("url", url)); // readiness loop disabled: original behaviour
        }
        com.aresstack.askai.browser.BrowserPageReadiness pr;
        try {
            pr = com.aresstack.askai.browser.BrowserPageReadiness.parse(
                    callBrowser("web_probe", args("url", url)));
        } catch (ToolInvoker.ToolFailure ex) {
            // A sidecar without web_probe: fall back to the single-step open (never fail the visit here).
            return callBrowser("web_open", args("url", url));
        }
        String family = familyOf(url);
        // "Erst auto": proactively clear a standard consent banner on EVERY page before judging — a common
        // cookie wall (OneTrust/Cookiebot/"accept all"/"alle akzeptieren") is dismissed here so its text
        // never bleeds into the read content, even when the DOM markers did not flag it as a banner.
        String clicked = callBrowser("web_dismiss_consent", args());
        if (clicked.startsWith("clicked")) {
            pr = reprobe();
            listener.status("[browser] consent action=" + consentActionOf(clicked)
                    + " resolved=" + !pr.consentPresent + " " + url);
        }
        // ONE classification per page (the model, when set, may recognise an obstruction the DOM selectors
        // missed); the subsequent waiting uses the cheap heuristic so we do not re-invoke the model per tick.
        PageReadinessJudge.Verdict verdict = readinessJudge.judge(pr);
        String blockReason = AccessBlockSignals.reason(pr);
        listener.status("readiness=" + verdict
                + (verdict == PageReadinessJudge.Verdict.ACCESS_BLOCKED ? " reason=" + blockReason : "")
                + " [text=" + pr.textLength + " challenge=" + pr.challengeVisible
                + (pr.challengePresent && !pr.challengeVisible ? " (artifact hidden)" : "")
                + (pr.challengePresent ? " evidence=" + pr.challengeMarker : "")
                + " consent=" + pr.consentPresent + "] " + url);
        renderHud(verdict.name(), "readiness=" + verdict
                        + (verdict == PageReadinessJudge.Verdict.ACCESS_BLOCKED ? " (" + blockReason + ")" : ""),
                false, ResearchHudState.NO_COUNTDOWN);
        switch (verdict) {
            case READABLE:
                return callBrowser("web_read", args()); // assigns the capture id acceptSource resolves
            case CONSENT_REQUIRED:
                return clearConsentThenRead(url, family, pr);
            case INTERACTIVE_CHALLENGE:
                if (!challengeWaitForUser) {
                    listener.status("skipping interactive-challenge page (wait-for-user disabled): " + url);
                    return null; // leave parked
                }
                listener.attention("CAPTCHA", family, url, false);
                return waitForUserThenRead(url); // a solvable challenge has no business timeout: cooperative wait
            case ACCESS_BLOCKED:
                // TERMINAL block: nothing to solve — never wait for the user, never accept. Skip this URL and
                // remember the domain so no other candidate on it is re-opened this run.
                blockedFamilies.add(family);
                listener.status("skipped blocked page (" + blockReason + "): " + url);
                return null;
            case UNREADABLE:
            default:
                return null; // leave parked (empty full text; the score still tells the user it needs reading)
        }
    }

    /** Cookie/consent: auto-dismiss up to the retry limit; if still blocked, tell the user where to click. */
    private String clearConsentThenRead(String url, String family,
                                        com.aresstack.askai.browser.BrowserPageReadiness pr)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        String candidate = pr.consentCandidate;
        for (int i = 0; i < maxReadinessRetries; i++) {
            String clicked = callBrowser("web_dismiss_consent", args());
            pr = reprobe();
            if (heuristicReadable(pr)) {
                return callBrowser("web_read", args());
            }
            if (!clicked.startsWith("clicked")) {
                break; // the heuristic can no longer clear it — hand it to the user
            }
        }
        // "erst auto, dann User": auto-dismiss could not clear it.
        if (!challengeWaitForUser) {
            listener.status("consent not clearable, parked: " + url);
            return null;
        }
        listener.attention("COOKIE", family, url + " — click: " + candidate, false);
        return waitForUserThenRead(url);
    }

    /**
     * Wait cooperatively for the user to clear a challenge / consent wall (no business timeout, cancel stays
     * immediate, the wait is compensated), re-probing each tick until the page is heuristically readable, then
     * read it. Returns {@code null} on cancel/stop (the page stays parked).
     */
    private String waitForUserThenRead(String url)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        long deadline = clock.currentTimeMillis() + HUD_USER_WAIT_TIMEOUT_MILLIS;
        while (true) {
            if (stopReasonNow() != null || cancelled.get()) {
                return null;
            }
            // HUD: show a live countdown and let the user Skip this page (the escape from a stuck wait) or
            // Pause. Skip / a lapsed countdown park the page (empty full text); READABLE-only acceptance holds.
            int remaining = (int) Math.max(0, (deadline - clock.currentTimeMillis()) / 1000L);
            renderHud("WAITING_FOR_USER", "Waiting for you to resolve this page", true, remaining);
            for (ResearchHudCommand command : pollHudCommands()) {
                if (command.type == ResearchHudCommand.Type.SKIP) {
                    listener.status("[browser] user skipped page: " + url);
                    skipNextInterPageDelay = true; // don't stall on the delay before the next page
                    return null;
                }
                if (command.type == ResearchHudCommand.Type.NEXT) {
                    // The user marks the obstruction RESOLVED (e.g. a solved CAPTCHA the reprobe hasn't caught,
                    // or a page they accept as-is): read it now — an explicit user override of the READABLE
                    // heuristic. The page still passes the term-match gate before it can be accepted.
                    listener.status("[browser] user marked the page resolved — reading now: " + url);
                    skipNextInterPageDelay = true;
                    return callBrowser("web_read", args());
                }
                applyHudSideEffect(command); // PAUSE/RESUME/SET_DELAY (the slider works during a wait too)
            }
            if (clock.currentTimeMillis() >= deadline) {
                listener.status("[browser] user-wait timed out, parked: " + url);
                return null;
            }
            waitedForUserMillis += tickWait();
            if (heuristicReadable(reprobe())) {
                return callBrowser("web_read", args());
            }
        }
    }

    /**
     * The cheap readable check used to gate every {@code web_read} (and thus every acceptance) on READABLE: a
     * page is only read when it has enough text AND no challenge/consent flag AND is not a terminal access block.
     * The block guard is essential — a Cloudflare 1020 page has plenty of text and its challenge marker clears on
     * reprobe, so without it the block page would be read + accepted (the source-10 bug).
     */
    boolean heuristicReadable(com.aresstack.askai.browser.BrowserPageReadiness pr) {
        return !pr.challengeVisible && !pr.consentPresent && !AccessBlockSignals.isBlocked(pr)
                && pr.textLength >= minReadableChars;
    }

    private com.aresstack.askai.browser.BrowserPageReadiness reprobe()
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return com.aresstack.askai.browser.BrowserPageReadiness.parse(callBrowser("web_reprobe", args()));
    }

    /**
     * The consent ACTION from a {@code web_dismiss_consent} result: {@code 'clicked:REJECT_ALL:<what>'} →
     * {@code REJECT_ALL} (also ONLY_NECESSARY / ACCEPT_ALL / CLOSE); an older {@code 'clicked:<sel>'} /
     * {@code 'clicked-text:<txt>'} → {@code CLICKED}. Purely for diagnostics.
     */
    private static String consentActionOf(String clicked) {
        if (clicked == null || !clicked.startsWith("clicked:")) {
            return "CLICKED";
        }
        String rest = clicked.substring("clicked:".length());
        int colon = rest.indexOf(':');
        String token = colon < 0 ? rest : rest.substring(0, colon);
        if (token.equals("REJECT_ALL") || token.equals("ONLY_NECESSARY") || token.equals("ACCEPT_ALL")
                || token.equals("CLOSE")) {
            return token;
        }
        return "CLICKED";
    }

    // ------------------------------------------------------------------ Research HUD (optional overlay)

    /**
     * Render a HUD state onto the current page; best-effort — a backend without the overlay simply ignores it.
     * A failure NEVER affects the research run, but it is LOGGED (never swallowed): the HUD is a prime suspect
     * for a browser crash, so its errors must be visible. Off entirely under the kill-switch.
     */
    private void renderHud(String phase, String status, boolean waiting, int countdownSeconds) {
        if (!hudEnabled) {
            return;
        }
        try {
            callBrowser("web_hud_render", args("state",
                    new ResearchHudState(phase, status, waiting, countdownSeconds, hudPaused,
                            (int) (hudDelayMillis / 1000L)).render()));
            lastHudErrorLine = null; // a success clears the throttle so the next distinct failure is logged
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException ex) {
            logHudFailure("render", ex);
        }
    }

    /** Drain overlay commands; best-effort. A failure is logged (not swallowed). Off under the kill-switch. */
    private List<ResearchHudCommand> pollHudCommands() {
        if (!hudEnabled) {
            return java.util.Collections.<ResearchHudCommand>emptyList();
        }
        try {
            List<ResearchHudCommand> commands = ResearchHudCommand.parseBatch(callBrowser("web_hud_poll", args()));
            lastHudErrorLine = null;
            return commands;
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException ex) {
            logHudFailure("poll", ex);
            return java.util.Collections.<ResearchHudCommand>emptyList();
        }
    }

    /** Log a HUD failure once per distinct cause (render/poll run every tick — do not flood the log). */
    private void logHudFailure(String stage, Throwable ex) {
        boolean endpoint = ex instanceof ToolInvoker.EndpointUnavailable;
        if (endpoint) {
            // The overlay call reaches the browser through the SAME restart-and-retry bridge as every other
            // browser call, so an EndpointUnavailable here means the browser is genuinely gone (a restart did
            // not recover it). End the run technically instead of silently swallowing it and looping forever.
            browserGone = true;
        }
        String line = "[browser-hud] " + stage + " failed"
                + (endpoint ? " (endpoint unavailable — sidecar may be dead)" : "") + " cause=" + describe(ex);
        if (!line.equals(lastHudErrorLine)) {
            lastHudErrorLine = line;
            listener.status(line);
        }
    }

    /** Compact type: message (&lt;- cause …) chain for diagnostics; bounded so a deep chain cannot flood a line. */
    private static String describe(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null && sb.length() < 400; t = t.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            if (t.getCause() == t) {
                break; // self-referential cause guard
            }
        }
        return sb.toString();
    }

    /** Apply PAUSE/RESUME/SET_DELAY from the overlay (SKIP/NEXT are handled contextually where a page/wait ends). */
    private void applyHudPauseResume() {
        for (ResearchHudCommand command : pollHudCommands()) {
            if (command.type == ResearchHudCommand.Type.NEXT
                    || command.type == ResearchHudCommand.Type.SKIP) {
                skipNextInterPageDelay = true; // "proceed now" → do not stall on the delay before the next page
            }
            applyHudSideEffect(command);
        }
    }

    /** Apply the non-contextual side effects of a HUD command (pause state + delay); ignores SKIP/NEXT. */
    private void applyHudSideEffect(ResearchHudCommand command) {
        if (command.type == ResearchHudCommand.Type.PAUSE) {
            hudPaused = true;
        } else if (command.type == ResearchHudCommand.Type.RESUME) {
            hudPaused = false;
        } else if (command.type == ResearchHudCommand.Type.SET_DELAY) {
            setHudDelayFrom(command.arg);
        }
    }

    /** Parse a slider value (seconds) into the clamped inter-page delay; a malformed value leaves it unchanged. */
    private void setHudDelayFrom(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return;
        }
        try {
            double seconds = Double.parseDouble(arg.trim());
            long millis = (long) (Math.max(0.0, seconds) * 1000.0);
            hudDelayMillis = Math.max(0L, Math.min(HUD_MAX_DELAY_MILLIS, millis));
        } catch (NumberFormatException ignored) {
            // keep the current delay
        }
    }

    /**
     * Wait the user-set inter-page delay before opening the next page. The delay is a deliberate slow-down for a
     * watching user, so it is NOT counted against the time budget (accumulated into {@code waitedForUserMillis},
     * like a manual wait). Cancel/stop, the NEXT button and the SKIP button all end it immediately; the slider can
     * be adjusted live. Returns a stop reason on cancel/stop, else {@code null} to proceed.
     */
    private ResearchStopReason interPageDelay() {
        if (skipNextInterPageDelay) {
            skipNextInterPageDelay = false; // a Skip/Next just fired — do not make the user wait the delay again
            return null;
        }
        if (hudDelayMillis <= 0) {
            return null;
        }
        long endAt = clock.currentTimeMillis() + hudDelayMillis;
        while (true) {
            if (cancelled.get()) {
                return ResearchStopReason.USER_CANCELLED;
            }
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            long now = clock.currentTimeMillis();
            if (now >= endAt) {
                return null;
            }
            int remaining = (int) Math.max(1, (endAt - now + 999L) / 1000L);
            renderHud("DELAY", "Waiting " + remaining + "s before the next page (Next skips)", false,
                    ResearchHudState.NO_COUNTDOWN);
            for (ResearchHudCommand command : pollHudCommands()) {
                if (command.type == ResearchHudCommand.Type.NEXT
                        || command.type == ResearchHudCommand.Type.SKIP) {
                    return null; // proceed to the next page now
                }
                applyHudSideEffect(command);
                if (command.type == ResearchHudCommand.Type.SET_DELAY) {
                    endAt = clock.currentTimeMillis() + hudDelayMillis; // live re-target
                }
            }
            if (hudPaused) {
                return null; // a pause during the delay abandons it; the loop-top pause gate takes over
            }
            waitedForUserMillis += tickWait(); // excluded from the time budget
        }
    }

    /** While the user paused navigation, wait (cancel-aware) instead of opening the next page. */
    private ResearchStopReason awaitResumeIfPaused() {
        boolean announced = false;
        while (hudPaused) {
            if (cancelled.get()) {
                return ResearchStopReason.USER_CANCELLED;
            }
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            if (!announced) {
                listener.status("[browser] paused by user");
                announced = true;
            }
            renderHud("PAUSED", "Paused — resume to continue", false, ResearchHudState.NO_COUNTDOWN);
            tickWait();
            applyHudPauseResume();
        }
        if (announced) {
            listener.status("[browser] resumed");
        }
        return null;
    }

    /** Sleep one probe interval (cancel stays immediate via the loop's checks); returns the time waited. */
    private long tickWait() {
        long tick = clock.currentTimeMillis();
        clock.sleepMillis(challengeProbeIntervalMillis);
        return Math.max(0, clock.currentTimeMillis() - tick);
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
        if (browserGone) {
            return ResearchStopReason.MCP_UNAVAILABLE; // browser closed/dead → end technically, never hang
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
