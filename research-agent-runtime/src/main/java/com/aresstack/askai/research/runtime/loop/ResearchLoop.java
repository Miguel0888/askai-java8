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

    public ResearchLoop(ToolInvoker browser, ToolInvoker research, ResearchRunBudget budget,
                        ResearchLoopClock clock, ResearchLoopListener listener, AtomicBoolean cancelled) {
        this.browser = browser;
        this.research = research;
        this.budget = budget;
        this.clock = clock;
        this.listener = listener;
        this.cancelled = cancelled;
        this.startedAt = clock.currentTimeMillis();
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
        try {
            ResearchStopReason gate = beforeToolCall();
            if (gate != null) {
                return gate;
            }
            String query = join(terms);
            listener.progress(progress, ResearchRunActivity.searching(query));
            String results = callBrowser("web_search", args("query", query));
            String providerHost = providerHostOf(results);
            if (!providerHost.isEmpty()) {
                searchProviderSites.add(siteOf(providerHost));
            }
            frontier.addAll(extractUrls(results));
        } catch (ToolInvoker.EndpointUnavailable ex) {
            return ResearchStopReason.MCP_UNAVAILABLE;
        } catch (ToolInvoker.ToolFailure ex) {
            progress.error();
        }

        while (true) {
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            if (frontier.isEmpty()) {
                return sufficientOr(ResearchStopReason.NO_RELEVANT_PATHS);
            }
            String url = frontier.remove(0);
            String canonical = canonicalish(url);
            if (progress.alreadyVisited(canonical)) {
                continue; // already visited → never navigate again
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
            String accepted = callResearch("source_accept", args("capture_id", captureId));
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
        if (clock.currentTimeMillis() - startedAt >= budget.getMaxDurationMillis()) {
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

    /** The {@code PROVIDER: <host>} line of a {@code web_search} result, or {@code ""}. */
    static String providerHostOf(String results) {
        for (String line : (results == null ? "" : results).split("\n")) {
            if (line.startsWith("PROVIDER: ")) {
                return line.substring("PROVIDER: ".length()).trim().toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private boolean isSearchProviderSite(String host) {
        return !host.isEmpty() && searchProviderSites.contains(siteOf(host));
    }

    /** Comparable "site" of a host ({@code www.bing.com} → {@code bing.com}); literal IPs stay as-is. */
    static String siteOf(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.matches("[0-9.:]+")) {
            return h;
        }
        String[] labels = h.split("\\.");
        return labels.length <= 2 ? h : labels[labels.length - 2] + "." + labels[labels.length - 1];
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
