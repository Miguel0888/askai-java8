package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic scheduler behaviour with a fake clock and a fake page gateway — no browser, no threads, no
 * sleeping. Covers the invariants that make the cooperative multi-tab design safe: the backend limits and
 * stagger are enforced, a fast later tab overtakes a slow earlier one, an await tick never closes tabs while
 * only a per-tab deadline does, cancel tears queue and pages down, late results of closed tabs are ignored,
 * and a recovery abort ends batches with an explicit terminal outcome.
 */
public class TabManagerTest {

    // ------------------------------------------------------------------ fakes

    private static final class FakeClock implements MillisClock {
        long now;

        public long nowMillis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    /** Per-URL scripted behaviour: when it becomes content-ready, and whether start/probe/read fail. */
    private static final class Script {
        long readyAtMillis = Long.MAX_VALUE; // never ready by default (stays LOADING)
        boolean startFails;
        boolean probeFatal;
        boolean readFatal;
        String title = "Title";
        String text = "a body long enough to clear the minimum readable characters threshold easily";
    }

    private static final class FakeHandle {
        final String url;
        final Script script;
        boolean closed;

        FakeHandle(String url, Script script) {
            this.url = url;
            this.script = script;
        }
    }

    private static final class FakeGateway implements TabPageGateway {
        final FakeClock clock;
        final Map<String, Script> scripts = new HashMap<String, Script>();
        int startCount;
        int readCount;
        int openPages;

        FakeGateway(FakeClock clock) {
            this.clock = clock;
        }

        Script script(String url) {
            Script s = scripts.get(url);
            if (s == null) {
                s = new Script();
                scripts.put(url, s);
            }
            return s;
        }

        public Object startNavigation(String url) throws BrowserException {
            startCount++;
            Script s = script(url);
            if (s.startFails) {
                throw new BrowserException("start failed for " + url);
            }
            openPages++;
            return new FakeHandle(url, s);
        }

        public ReadinessProbe probe(Object handle) throws BrowserException {
            final FakeHandle h = (FakeHandle) handle;
            if (h.script.probeFatal) {
                throw new BrowserException("probe fatal");
            }
            final long len = clock.now >= h.script.readyAtMillis ? 1000L : 0L;
            return new ReadinessProbe() {
                public long bodyTextLength() {
                    return len;
                }

                public boolean anySelectorPresent(List<String> cssSelectors) {
                    return false; // exercise the generic content-stability path
                }
            };
        }

        public PlaywrightPageState read(Object handle) throws BrowserException {
            readCount++;
            FakeHandle h = (FakeHandle) handle;
            if (h.script.readFatal) {
                throw new BrowserException("read fatal");
            }
            return new PlaywrightPageState(h.url, h.script.title, h.script.text,
                    Collections.<PlaywrightPageState.Anchor>emptyList());
        }

        public void closeTab(Object handle) {
            if (handle == null) {
                return;
            }
            FakeHandle h = (FakeHandle) handle;
            if (!h.closed) {
                h.closed = true;
                openPages--;
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static PageReadiness readiness(long tabDeadlineMillis) {
        // settlePollCount 1 keeps ticks short; min 48 chars; tab deadline drives per-tab timeout.
        return new PageReadiness(new GenericContentReadinessStrategy(),
                new PageReadinessPolicy(100, 1, tabDeadlineMillis, 48));
    }

    private static TabManager manager(TabSchedulingPolicy scheduling, FakeGateway gw, FakeClock clock,
                                      long tabDeadlineMillis) {
        return new TabManager(scheduling, readiness(tabDeadlineMillis), gw, clock);
    }

    private static AwaitStep tick(TabManager m, String batchId, int times) {
        AwaitStep step = null;
        for (int i = 0; i < times; i++) {
            step = m.pollNextReady(batchId);
        }
        return step;
    }

    /** Poll until the batch yields something other than PENDING (bounded), returning that step. */
    private static AwaitStep pollUntilSettled(TabManager m, String batchId) {
        AwaitStep step = m.pollNextReady(batchId);
        for (int i = 0; i < 20 && step.getOutcome() == AwaitStep.Outcome.PENDING; i++) {
            step = m.pollNextReady(batchId);
        }
        return step;
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void enforcesMaxOpenTabs() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        // maxOpenTabs=2; never-ready tabs keep both slots occupied so no third ever starts.
        TabManager m = manager(new TabSchedulingPolicy(2, 5, 16, 0), gw, clock, 1_000_000);
        String batch = m.openBatch(Arrays.asList("u1", "u2", "u3", "u4"), 5).getBatchId();
        tick(m, batch, 8);
        assertEquals("only maxOpenTabs pages ever open", 2, gw.startCount);
        assertEquals(2, gw.openPages);
    }

    @Test
    public void enforcesMaxConcurrentNavigations() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 2, 16, 0), gw, clock, 1_000_000);
        String batch = m.openBatch(Arrays.asList("u1", "u2", "u3", "u4"), 10).getBatchId();
        tick(m, batch, 8);
        assertEquals("navigation concurrency is capped", 2, gw.startCount);
    }

    @Test
    public void enforcesNavigationStagger() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 10, 16, 300), gw, clock, 1_000_000);
        String batch = m.openBatch(Arrays.asList("u1", "u2", "u3"), 10).getBatchId();

        m.pollNextReady(batch);                 // t=0 → first start
        assertEquals(1, gw.startCount);
        m.pollNextReady(batch);                 // t=0 → inside stagger, no start
        assertEquals(1, gw.startCount);
        clock.advance(300);
        m.pollNextReady(batch);                 // t=300 → second start
        assertEquals(2, gw.startCount);
        clock.advance(299);
        m.pollNextReady(batch);                 // t=599 → still inside stagger
        assertEquals(2, gw.startCount);
        clock.advance(1);
        m.pollNextReady(batch);                 // t=600 → third start
        assertEquals(3, gw.startCount);
    }

    @Test
    public void fasterLaterTabOvertakesSlowerEarlierTab() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 10, 16, 0), gw, clock, 1_000_000);
        gw.script("slow").readyAtMillis = 1_000_000; // effectively never in this test
        gw.script("fast").readyAtMillis = 0;         // ready from the start
        String batch = m.openBatch(Arrays.asList("slow", "fast"), 10).getBatchId();

        AwaitStep step = null;
        for (int i = 0; i < 10 && (step == null || step.getOutcome() == AwaitStep.Outcome.PENDING); i++) {
            step = m.pollNextReady(batch);
        }
        assertNotNull(step);
        assertEquals(AwaitStep.Outcome.READY, step.getOutcome());
        assertEquals("the fast tab is returned even though the slow tab started first",
                "fast", step.getReadyTab().requestedUrl);
        assertEquals("fast", step.getReadyTab().state.url);
    }

    @Test
    public void awaitTicksNeverCloseTabs() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        // Tabs are not ready yet but nowhere near their (large) deadline: many ticks must close nothing.
        TabManager m = manager(new TabSchedulingPolicy(10, 10, 16, 0), gw, clock, 1_000_000);
        String batch = m.openBatch(Arrays.asList("u1", "u2"), 10).getBatchId();
        for (int i = 0; i < 20; i++) {
            assertEquals(AwaitStep.Outcome.PENDING, m.pollNextReady(batch).getOutcome());
        }
        assertEquals("both tabs started", 2, gw.startCount);
        assertEquals("no tab was closed by mere waiting", 2, gw.openPages);
    }

    @Test
    public void perTabDeadlineTimesOutExactlyThatTab() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        // One in flight at a time so A starts, times out, then B starts.
        TabManager m = manager(new TabSchedulingPolicy(10, 1, 16, 0), gw, clock, 500);
        String batch = m.openBatch(Arrays.asList("a", "b"), 1).getBatchId();

        m.pollNextReady(batch);                 // t=0 → A starts (deadline 500)
        assertEquals(1, gw.startCount);
        clock.advance(500);
        m.pollNextReady(batch);                 // t=500 → A times out & closes (B blocked this tick: A still in flight when starts are checked)
        m.pollNextReady(batch);                 // next tick → B starts, now that A's slot is free
        assertEquals("A closed, B opened", 2, gw.startCount);
        assertEquals("exactly one page open (B); A was closed on timeout", 1, gw.openPages);
    }

    @Test
    public void closeBatchTearsDownQueueAndPages() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 1, 16, 0), gw, clock, 1_000_000);
        String batch = m.openBatch(Arrays.asList("a", "b", "c"), 1).getBatchId();
        m.pollNextReady(batch);                 // one tab loading, two still queued

        m.closeBatch(batch);
        assertEquals("all pages closed", 0, gw.openPages);
        assertEquals("a cancelled batch drains", AwaitStep.Outcome.DRAINED,
                m.pollNextReady(batch).getOutcome());
    }

    @Test
    public void lateResultOfClosedTabIsIgnored() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 10, 16, 0), gw, clock, 1_000_000);
        gw.script("a").readyAtMillis = 0;       // would be ready immediately if left alone
        String batch = m.openBatch(Collections.singletonList("a"), 10).getBatchId();
        m.pollNextReady(batch);                 // A starts loading

        m.closeTab(batch + "-tab-1");           // cancel it before it is ever read
        AwaitStep step = tick(m, batch, 5);
        assertEquals("nothing to produce once the only tab is closed",
                AwaitStep.Outcome.DRAINED, step.getOutcome());
        assertEquals("the closed tab's page was never read", 0, gw.readCount);
        assertEquals(0, gw.openPages);
    }

    @Test
    public void recoveryAbortEndsEveryBatchWithExplicitOutcome() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 10, 16, 0), gw, clock, 1_000_000);
        String b1 = m.openBatch(Arrays.asList("a", "b"), 10).getBatchId();
        String b2 = m.openBatch(Collections.singletonList("c"), 10).getBatchId();
        tick(m, b1, 3);
        tick(m, b2, 3);

        m.abortAllForRecovery();

        assertEquals(AwaitStep.Outcome.BATCH_ABORTED_RECOVERY, m.pollNextReady(b1).getOutcome());
        assertEquals(AwaitStep.Outcome.BATCH_ABORTED_RECOVERY, m.pollNextReady(b2).getOutcome());
        assertEquals("every page released by recovery", 0, gw.openPages);
    }

    @Test
    public void readsReadyTabsThenDrains() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 10, 16, 0), gw, clock, 1_000_000);
        gw.script("a").readyAtMillis = 0;
        gw.script("b").readyAtMillis = 0;
        String batch = m.openBatch(Arrays.asList("a", "b"), 10).getBatchId();

        assertEquals(AwaitStep.Outcome.READY, pollUntilSettled(m, batch).getOutcome());
        assertEquals(AwaitStep.Outcome.READY, pollUntilSettled(m, batch).getOutcome());
        assertEquals(AwaitStep.Outcome.DRAINED, pollUntilSettled(m, batch).getOutcome());
        assertEquals("both tabs were read", 2, gw.readCount);
        assertEquals("both pages closed after reading", 0, gw.openPages);
    }

    @Test
    public void clampsRequestedConcurrencyToBackendCeiling() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 3, 16, 0), gw, clock, 1_000_000);
        OpenBatchResult result = m.openBatch(Arrays.asList("a", "b"), 99);
        assertEquals("the model can never raise the ceiling", 3, result.getEffectiveConcurrency());
    }

    @Test
    public void capsBatchSizeAndDeduplicates() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 3, 2, 0), gw, clock, 1_000_000);
        OpenBatchResult result = m.openBatch(Arrays.asList("a", "a", "b", "c"), 3);
        assertEquals("maxBatchUrls caps acceptance", Arrays.asList("a", "b"), result.getAcceptedUrls());
        assertTrue("duplicate and over-cap urls are rejected", result.getRejectedUrls().contains("a"));
        assertTrue(result.getRejectedUrls().contains("c"));
    }

    @Test
    public void technicalStartFailureCountsTowardRecoverySignal() {
        FakeClock clock = new FakeClock();
        FakeGateway gw = new FakeGateway(clock);
        TabManager m = manager(new TabSchedulingPolicy(10, 3, 16, 0), gw, clock, 1_000_000);
        gw.script("bad1").startFails = true;
        gw.script("bad2").startFails = true;
        String batch = m.openBatch(Arrays.asList("bad1", "bad2"), 3).getBatchId();
        AwaitStep step = tick(m, batch, 4);
        assertEquals("all-failed batch reports BATCH_FAILED", AwaitStep.Outcome.BATCH_FAILED,
                step.getOutcome());
        assertTrue("consecutive technical failures accumulate", m.consecutiveTechnicalFailures() >= 2);
    }
}
