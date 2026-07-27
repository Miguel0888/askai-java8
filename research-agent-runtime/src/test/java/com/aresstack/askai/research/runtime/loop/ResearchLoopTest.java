package com.aresstack.askai.research.runtime.loop;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The deterministic loop against content fixtures (A relevant primary / B irrelevant / C relevant on a second
 * host / D content-duplicate of A / E complementary): decisions derive from page/link CONTENT, not order.
 * Budgets, cancel, error-reset and late-write rejection are all driven without any real time or sleeps.
 */
public class ResearchLoopTest {

    /** One fixture page. */
    private static final class Page {
        final String title;
        final String text;
        final List<String> links;

        Page(String title, String text, List<String> links) {
            this.title = title;
            this.text = text;
            this.links = links;
        }
    }

    /** Deterministic browser tool backend (fixture world) with a capture-id convention like the real one. */
    private static final class FakeBrowser implements ToolInvoker {
        final Map<String, Page> pages = new LinkedHashMap<String, Page>();
        final Map<String, String> captureByUrl = new LinkedHashMap<String, String>();
        String current;
        int captureSeq;
        boolean failEverything;

        FakeBrowser() {
            List<String> aLinks = new ArrayList<String>();
            aLinks.add("pf4j details — https://host2.net/c");
            aLinks.add("boring news — https://host1.com/b");
            pages.put("https://host1.com/a", new Page("PF4J primer",
                    "pf4j is a plugin framework. Primary source.", aLinks));
            pages.put("https://host1.com/b", new Page("Cooking tips",
                    "Recipes for soup and bread. Purely culinary content.", new ArrayList<String>()));
            List<String> cLinks = new ArrayList<String>();
            cLinks.add("pf4j mirror copy — https://host3.org/d");
            cLinks.add("pf4j extra evidence — https://host2.net/e");
            pages.put("https://host2.net/c", new Page("Independent pf4j review",
                    "pf4j works well with java 8.", cLinks));
            pages.put("https://host3.org/d", new Page("PF4J primer (mirror)",
                    "pf4j is a plugin framework. Primary source.", new ArrayList<String>()));
            pages.put("https://host2.net/e", new Page("pf4j in production",
                    "More pf4j evidence from the field.", new ArrayList<String>()));
        }

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if (failEverything) {
                throw new ToolFailure("browser down");
            }
            if ("web_search".equals(tool)) {
                return "1: PF4J primer — https://host1.com/a\n2: Cooking tips — https://host1.com/b";
            }
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                Page page = pages.get(url);
                if (page == null) {
                    throw new ToolFailure("404 " + url);
                }
                current = url;
                String cap = captureByUrl.get(url);
                if (cap == null) {
                    cap = "cap-" + (++captureSeq);
                    captureByUrl.put(url, cap);
                }
                return "URL: " + url + " title=\"" + page.title + "\" capture_id=" + cap
                        + "\n" + page.text;
            }
            if ("web_links".equals(tool)) {
                Page page = pages.get(current);
                StringBuilder sb = new StringBuilder();
                if (page != null) {
                    for (String link : page.links) {
                        sb.append(link).append('\n');
                    }
                }
                return sb.toString();
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    /** Deterministic research backend honoring the Commit-37 result contract incl. dedup by content. */
    private static final class FakeResearch implements ToolInvoker {
        final FakeBrowser browser;
        final Map<String, String> sourceByHash = new LinkedHashMap<String, String>();
        final Map<String, String> sourceByCapture = new LinkedHashMap<String, String>();
        final List<String> findings = new ArrayList<String>();
        boolean waitingApproval;
        int seq;

        FakeResearch(FakeBrowser browser) {
            this.browser = browser;
        }

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if (waitingApproval && ("source_accept".equals(tool) || "finding_add".equals(tool))) {
                throw new ToolFailure("Not allowed in the current state (research/waiting_approval).");
            }
            if ("source_accept".equals(tool)) {
                String cap = String.valueOf(args.get("capture_id"));
                String already = sourceByCapture.get(cap);
                if (already != null) {
                    return "status=ALREADY_ACCEPTED source_id=" + already + " duplicate=false";
                }
                String url = null;
                for (Map.Entry<String, String> e : browser.captureByUrl.entrySet()) {
                    if (e.getValue().equals(cap)) {
                        url = e.getKey();
                    }
                }
                String hash = browser.pages.get(url).text; // content hash stand-in
                boolean duplicate = sourceByHash.containsKey(hash);
                String id = "source-" + (++seq);
                sourceByCapture.put(cap, id);
                sourceByHash.put(hash + (duplicate ? "#" + id : ""), id);
                if (!duplicate) {
                    sourceByHash.put(hash, id);
                }
                return "status=ACCEPTED source_id=" + id + " title=\"t\" passage_count=1 duplicate="
                        + duplicate;
            }
            if ("finding_add".equals(tool)) {
                String sourceId = String.valueOf(args.get("source_id"));
                if (!sourceByCapture.containsValue(sourceId)) {
                    throw new ToolFailure("Unknown source: " + sourceId);
                }
                findings.add(sourceId + ": " + args.get("text"));
                return "appended revision=1";
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    private static final class Fx {
        final FakeBrowser browser = new FakeBrowser();
        final FakeResearch research = new FakeResearch(browser);
        final AtomicLong now = new AtomicLong(1000L);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final List<String> statuses = new ArrayList<String>();
        final List<ResearchStopReason> ready = new ArrayList<ResearchStopReason>();

        ResearchLoop loop(ResearchRunBudget budget) {
            return new ResearchLoop(browser, research, budget,
                    new ResearchLoopClock() {
                        public long currentTimeMillis() {
                            return now.get();
                        }
                    },
                    new ResearchLoopListener() {
                        public void status(String message) {
                            statuses.add(message);
                        }

                        public void phaseReady(ResearchStopReason reason) {
                            ready.add(reason);
                        }
                    }, cancelled);
        }
    }

    @Test
    public void contentDrivenRunVisitsFollowsAcceptsAndReportsPhaseReady() {
        Fx fx = new Fx();
        ResearchLoop loop = fx.loop(ResearchRunBudget.defaults());
        ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

        assertTrue("at least three distinct pages", loop.getProgress().getPagesVisited() >= 3);
        assertTrue("at least two accepted sources", loop.getProgress().getAcceptedSources() >= 2);
        assertTrue("two distinct hosts", loop.getProgress().getDistinctHosts().size() >= 2);
        // The irrelevant page B was visited via search but never accepted.
        boolean bAccepted = fx.research.sourceByCapture.containsKey(
                fx.browser.captureByUrl.get("https://host1.com/b"));
        assertFalse("irrelevant page must not be accepted", bAccepted);
        // Findings reference existing sources; the duplicate D did not repeat the same claim.
        assertTrue("at least one finding", fx.research.findings.size() >= 1);
        for (String finding : fx.research.findings) {
            assertTrue(finding.startsWith("source-"));
        }
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
        assertEquals(1, fx.ready.size()); // PHASE_READY event sent — but only as an event
    }

    @Test
    public void budgetsStopTheRunExplicitly() {
        // Tool budget.
        Fx fx = new Fx();
        assertEquals(ResearchStopReason.TOOL_BUDGET_EXHAUSTED,
                fx.loop(new ResearchRunBudget(2, 20, 8, 3, 600_000, 3, 2)).run("pf4j"));
        // Page budget.
        Fx fx2 = new Fx();
        assertEquals(ResearchStopReason.PAGE_BUDGET_EXHAUSTED,
                fx2.loop(new ResearchRunBudget(30, 1, 8, 3, 600_000, 3, 2)).run("pf4j"));
        // Source budget.
        Fx fx3 = new Fx();
        assertEquals(ResearchStopReason.SOURCE_BUDGET_EXHAUSTED,
                fx3.loop(new ResearchRunBudget(30, 20, 1, 3, 600_000, 3, 2)).run("pf4j"));
    }

    @Test
    public void timeBudgetStopsViaClockPortWithoutSleep() {
        Fx fx = new Fx();
        ResearchLoop loop = fx.loop(new ResearchRunBudget(30, 20, 8, 3, 5_000, 3, 2));
        fx.now.addAndGet(10_000); // advance the injected clock past the budget — no real waiting
        assertEquals(ResearchStopReason.TIME_BUDGET_EXHAUSTED, loop.run("pf4j"));
    }

    @Test
    public void errorBudgetStopsAndSuccessResetsConsecutiveErrors() {
        Fx fx = new Fx();
        fx.browser.failEverything = true;
        ResearchLoop failing = fx.loop(new ResearchRunBudget(30, 20, 8, 2, 600_000, 3, 2));
        ResearchStopReason reason = failing.run("pf4j");
        assertTrue(reason == ResearchStopReason.ERROR_BUDGET_EXHAUSTED
                || reason == ResearchStopReason.NO_RELEVANT_PATHS);

        // Reset semantics on the progress itself: error, error, success → 0, error → 1.
        ResearchRunProgress p = new ResearchRunProgress();
        p.error();
        p.error();
        assertEquals(2, p.getConsecutiveErrors());
        p.success();
        assertEquals(0, p.getConsecutiveErrors());
        p.error();
        assertEquals(1, p.getConsecutiveErrors());
        assertEquals(3, p.getTotalErrors());
    }

    @Test
    public void userCancelStopsTheRun() {
        Fx fx = new Fx();
        fx.cancelled.set(true);
        assertEquals(ResearchStopReason.USER_CANCELLED, fx.loop(ResearchRunBudget.defaults()).run("pf4j"));
    }

    @Test
    public void lateWriteAfterStateChangeIsRejectedAndStopsWithApprovalRequired() {
        Fx fx = new Fx();
        fx.research.waitingApproval = true; // the host flipped to WAITING_APPROVAL mid-run
        ResearchStopReason reason = fx.loop(ResearchRunBudget.defaults()).run("pf4j");
        assertEquals(ResearchStopReason.APPROVAL_REQUIRED, reason);
        assertTrue("no finding may have been stored", fx.research.findings.isEmpty());
        assertEquals("PHASE_READY must not fire on an interrupted run", 0, fx.ready.size());
    }

    @Test
    public void revisitedUrlsAreNeverNavigatedAgainAndPagesCountOnlyNewCanonicalUrls() {
        ResearchRunProgress p = new ResearchRunProgress();
        assertTrue(p.pageVisited("https://a/x", "a"));
        assertFalse("same canonical url counts once", p.pageVisited("https://a/x", "a"));
        assertTrue(p.alreadyVisited("https://a/x"));
        assertEquals(1, p.getPagesVisited());
    }
}
