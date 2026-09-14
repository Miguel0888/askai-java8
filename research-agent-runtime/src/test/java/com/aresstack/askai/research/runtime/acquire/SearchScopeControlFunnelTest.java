package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.rerank.EngineOrderReranker;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SC1 funnel pins against the REAL WebSearchApplicationService: a CLEAR canonical OUT SERP hit
 * is removed BEFORE web_open and BEFORE source_park; a loaded OUT page is neither accepted nor
 * link-expanded; begin() failure is fail-closed BEFORE any engine call; INACTIVE is a true
 * no-op fast path (zero evaluate calls).
 */
public class SearchScopeControlFunnelTest {

    private static final class ScriptedScope implements SearchScopeControlPort {
        final List<String> lanes = new ArrayList<String>();
        final java.util.Map<String, java.util.Set<String>> outIdsByLane =
                new java.util.HashMap<String, java.util.Set<String>>();
        boolean inactive;
        boolean failBegin;
        boolean ended;

        void out(String lane, String id) {
            java.util.Set<String> ids = outIdsByLane.get(lane);
            if (ids == null) {
                ids = new java.util.HashSet<String>();
                outIdsByLane.put(lane, ids);
            }
            ids.add(id);
        }

        public Session begin() throws ToolInvoker.ToolFailure {
            if (failBegin) {
                throw new ToolInvoker.ToolFailure("UNAVAILABLE no embedding model");
            }
            return inactive ? new Session(false, null, "INACTIVE outAnchors=0")
                    : new Session(true, "ss-test", "handle=ss-test outAnchors=1");
        }

        public List<Decision> evaluate(String handle, String lane, List<Item> items) {
            lanes.add(lane);
            java.util.Set<String> outIds = outIdsByLane.get(lane);
            List<Decision> decisions = new ArrayList<Decision>();
            for (Item item : items) {
                decisions.add(new Decision(item.id,
                        outIds != null && outIds.contains(item.id), false, "GUI"));
            }
            return decisions;
        }

        public void end(String handle) {
            ended = true;
        }
    }

    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> tools = new ArrayList<String>();
        final List<String> openedUrls = new ArrayList<String>();
        String pageReply = "";

        public String call(String tool, Map<String, Object> args) {
            tools.add(tool);
            if ("web_open".equals(tool)) {
                openedUrls.add(String.valueOf(args.get("url")));
                return pageReply;
            }
            return "";
        }
    }

    private static final class RecordingAcceptance implements SourceAcceptancePort {
        final List<String> parked = new ArrayList<String>();
        final List<String> accepted = new ArrayList<String>();

        public String accept(String captureId) {
            accepted.add(captureId);
            return "accepted " + captureId;
        }

        public void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    private static SearchResultCandidate candidate(String url, String title, String snippet) {
        return new SearchResultCandidate("c-" + url, "s1", url, url, title, snippet,
                "example.com", 1, "r1", "b1", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList());
    }

    private static SearchStrategy twoHitStrategy(final int[] calls) {
        return new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request,
                                              CancellationSignal cancellation,
                                              SearchBudgetGate budget) {
                calls[0]++;
                return new InitialSearchResult(Arrays.asList(
                        candidate("https://kept.example/scheduling",
                                "FreeRTOS Scheduling Guide", "freertos scheduling"),
                        candidate("https://out.example/gui",
                                "Java Swing GUI Tutorial", "desktop windows toolkit")),
                        new ArrayList<String>(),
                        Collections.<com.aresstack.askai.browser.search.repair
                                .SearchChallengeState>emptyList(),
                        new ArrayList<String>());
            }
        };
    }

    private static WebSearchApplicationService service(RecordingBrowser browser,
            SearchStrategy strategy, RecordingAcceptance acceptance, final List<String> status) {
        return new WebSearchApplicationService(browser, ResearchRunBudget.defaults(),
                new ResearchRunProgress(),
                new ResearchLoopClock() {
                    long now;

                    public long currentTimeMillis() {
                        return now;
                    }

                    public void sleepMillis(long millis) {
                        now += Math.max(1L, millis);
                    }
                },
                new ResearchLoopListener() {
                    public void status(String message) {
                        status.add(message);
                    }

                    public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
                    }

                    public void phaseReady(ResearchStopReason reason) {
                    }

                    public void attention(String reason, String family, String url,
                                          boolean resolved) {
                    }
                },
                new AtomicBoolean(false), strategy, null, new EngineOrderReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(
                            WebSearchApplicationService.AcceptedSource source,
                            WebSearchApplicationService.ToolBudget budget) {
                        return null;
                    }
                },
                false, 0 /* readiness loop off: web_open verbatim */, 48);
    }

    @Test
    public void aClearOutSerpHitIsRemovedBeforeWebOpenAndBeforePark() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.out("serp", "https://out.example/gui");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("the SERP lane was judged", scope.lanes.contains("serp"));
        assertEquals("only the kept hit is parked",
                Collections.singletonList("https://kept.example/scheduling"),
                acceptance.parked);
        assertEquals("the OUT hit is NEVER opened",
                Collections.singletonList("https://kept.example/scheduling"),
                browser.openedUrls);
        assertTrue("the skip is observable", status.toString().contains(
                "search-scope skip candidate=\"Java Swing GUI Tutorial\" "
                        + "relation=LIKELY_OUT authority=CANONICAL_OUT nearest=\"GUI\""));
        assertTrue("the snapshot is released", scope.ended);
    }

    @Test
    public void aLoadedOutPageIsNeitherAcceptedNorLinkExpanded() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        // The page IS query-relevant (lexical fallback matches the terms) — only the SCOPE
        // rejects it: the last guard against wrong SERP snippets.
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"FreeRTOS Scheduling\"\nfreertos scheduling deep dive";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.out("serp", "https://out.example/gui");
        scope.out("page", "https://kept.example/scheduling"); // page-lane verdict: OUT
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue(scope.lanes.contains("page"));
        assertTrue("never accepted", acceptance.accepted.isEmpty());
        assertFalse("links never harvested", browser.tools.contains("web_links"));
        assertTrue(status.toString().contains(
                "search-scope page OUT url=https://kept.example/scheduling nearest=\"GUI\" "
                        + "-> not accepted, not expanded"));
    }

    @Test
    public void aFailedBeginEndsFailClosedBeforeAnyEngineCall() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.failBegin = true;
        service.setScopeControl(scope);

        ResearchStopReason reason = service.execute("freertos scheduling");

        assertEquals(ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE, reason);
        assertEquals("no engine call after a fail-closed begin", 0, calls[0]);
        assertTrue(browser.openedUrls.isEmpty());
    }

    @Test
    public void inactiveIsATrueNoOpFastPath() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true;
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("zero evaluate calls on the fast path", scope.lanes.isEmpty());
        assertEquals("baseline behaviour: BOTH hits parked", 2, acceptance.parked.size());
        assertEquals("baseline behaviour: BOTH hits opened", 2, browser.openedUrls.size());
    }
}
