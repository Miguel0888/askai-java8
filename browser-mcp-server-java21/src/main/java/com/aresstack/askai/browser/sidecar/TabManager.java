package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cooperative background-tab scheduler. Pure orchestration over a {@link TabPageGateway} and a
 * {@link MillisClock} — no threads, no Playwright, no sleeping — so its behaviour (limits, stagger, a fast
 * later tab overtaking a slow earlier one, per-tab timeouts, cancel, ignoring late results, recovery) is fully
 * deterministic under test. In production every public method is invoked inside ONE short
 * {@link PlaywrightThread#call}; because a tick starts at most one navigation and does one probe per loading
 * tab, the Playwright thread is never held long, and the caller waits BETWEEN ticks off-thread so cancel/close
 * can interleave.
 *
 * <p>Two timeouts stay strictly separate: the caller's await-budget (turns repeated {@link AwaitStep.Outcome#PENDING}
 * into a wait-timeout, closing nothing) is the caller's concern; the per-tab deadline here is the only thing
 * that moves a tab to {@link TabState#TIMED_OUT} and closes its page. Backend limits are enforced here, never
 * trusted from the caller.</p>
 */
final class TabManager {

    private final TabSchedulingPolicy scheduling;
    private final PageReadiness readiness;
    private final TabPageGateway gateway;
    private final MillisClock clock;

    private final Map<String, Batch> batches = new LinkedHashMap<String, Batch>();
    private int batchSeq;
    private int tabSeq;
    private long lastNavigationStartMillis = Long.MIN_VALUE;
    private int consecutiveTechnicalFailures;

    TabManager(TabSchedulingPolicy scheduling, PageReadiness readiness, TabPageGateway gateway,
               MillisClock clock) {
        this.scheduling = scheduling;
        this.readiness = readiness;
        this.gateway = gateway;
        this.clock = clock;
    }

    private static final class Batch {
        final String id;
        final int effectiveConcurrency;
        final List<BrowserTab> tabs = new ArrayList<BrowserTab>();
        AwaitStep.Outcome terminal; // non-null once closed or recovery-aborted

        Batch(String id, int effectiveConcurrency) {
            this.id = id;
            this.effectiveConcurrency = effectiveConcurrency;
        }
    }

    // ------------------------------------------------------------------ registration

    /** Register a batch (URLs deduplicated, capped at {@code maxBatchUrls}); nothing navigates yet. */
    OpenBatchResult openBatch(List<String> urls, int requestedConcurrency) {
        int effConc = scheduling.effectiveConcurrency(requestedConcurrency);
        String batchId = "batch-" + (++batchSeq);
        Batch batch = new Batch(batchId, effConc);
        List<String> accepted = new ArrayList<String>();
        List<String> rejected = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String raw : urls == null ? new ArrayList<String>() : urls) {
            String url = raw == null ? "" : raw.trim();
            if (url.isEmpty() || !seen.add(url)) {
                if (!url.isEmpty()) {
                    rejected.add(url); // duplicate within the batch
                }
                continue;
            }
            if (accepted.size() >= scheduling.getMaxBatchUrls()) {
                rejected.add(url); // over the backend batch cap
                continue;
            }
            accepted.add(url);
            batch.tabs.add(new BrowserTab(batchId + "-tab-" + (++tabSeq), url));
        }
        batches.put(batchId, batch);
        return new OpenBatchResult(batchId, accepted, rejected, effConc);
    }

    // ------------------------------------------------------------------ cooperative tick

    /** Advance the whole session one step, then report the next ready tab of {@code batchId} (if any). */
    AwaitStep pollNextReady(String batchId) {
        Batch batch = batches.get(batchId);
        if (batch == null) {
            return AwaitStep.of(AwaitStep.Outcome.DRAINED);
        }
        if (batch.terminal != null) {
            return AwaitStep.of(batch.terminal);
        }
        stepScheduler();
        BrowserTab ready = firstReady(batch);
        if (ready != null) {
            return readReadyTab(ready);
        }
        return exhaustionOutcome(batch);
    }

    private void stepScheduler() {
        startOneQueuedNavigation();
        probeLoadingTabs();
    }

    /**
     * Start AT MOST one queued navigation, honouring global {@code maxOpenTabs} / {@code maxConcurrentNavigations},
     * the per-batch effective concurrency, and the navigation stagger. One-per-tick with the caller's between-tick
     * spacing is exactly what produces staggered, non-bursty starts.
     */
    private void startOneQueuedNavigation() {
        if (countAcross(true) >= scheduling.getMaxOpenTabs()) {
            return; // no free open-tab slot
        }
        if (countAcross(false) >= scheduling.getMaxConcurrentNavigations()) {
            return; // navigation concurrency saturated
        }
        long now = clock.nowMillis();
        if (lastNavigationStartMillis != Long.MIN_VALUE
                && now - lastNavigationStartMillis < scheduling.getNavigationStaggerMillis()) {
            return; // still inside the stagger window (sentinel ⇒ no navigation started yet, always allowed)
        }
        for (Batch batch : batches.values()) {
            if (batch.terminal != null) {
                continue;
            }
            if (inFlightIn(batch) >= batch.effectiveConcurrency) {
                continue; // this batch is at its own concurrency ceiling
            }
            BrowserTab queued = firstInState(batch, TabState.QUEUED);
            if (queued == null) {
                continue;
            }
            beginNavigation(queued, now);
            return; // one per tick
        }
    }

    private void beginNavigation(BrowserTab tab, long now) {
        tab.state = TabState.NAVIGATING;
        lastNavigationStartMillis = now;
        try {
            tab.handle = gateway.startNavigation(tab.requestedUrl);
            tab.state = TabState.LOADING;
            tab.deadlineMillis = now + readiness.policy().getReadinessTimeoutMillis();
            consecutiveTechnicalFailures = 0;
        } catch (BrowserException failedToStart) {
            tab.state = TabState.FAILED;
            consecutiveTechnicalFailures++;
        }
    }

    private void probeLoadingTabs() {
        long now = clock.nowMillis();
        for (Batch batch : batches.values()) {
            if (batch.terminal != null) {
                continue;
            }
            for (BrowserTab tab : batch.tabs) {
                if (tab.state != TabState.LOADING) {
                    continue;
                }
                if (now >= tab.deadlineMillis) {
                    // Per-tab deadline: expected, not a technical fault — close and discard, never reuse.
                    gateway.closeTab(tab.handle);
                    tab.state = TabState.TIMED_OUT;
                    continue;
                }
                try {
                    ReadinessLabel label = readiness.probeOnce(gateway.probe(tab.handle), tab.readiness);
                    if (label.isSettled()) {
                        tab.state = TabState.READY;
                        tab.readinessLabel = label;
                        consecutiveTechnicalFailures = 0;
                    }
                } catch (BrowserException fatal) {
                    // The gateway reports only FATAL errors here (it swallows transient mid-navigation ones as
                    // "not ready yet"); a fatal probe means the page is unusable.
                    gateway.closeTab(tab.handle);
                    tab.state = TabState.FAILED;
                    consecutiveTechnicalFailures++;
                }
            }
        }
    }

    /** Read a READY tab atomically in this tick, then close its page — no separate read can race it. */
    private AwaitStep readReadyTab(BrowserTab tab) {
        tab.state = TabState.READING;
        try {
            PlaywrightPageState state = gateway.read(tab.handle);
            gateway.closeTab(tab.handle);
            tab.state = TabState.COMPLETED;
            consecutiveTechnicalFailures = 0;
            return AwaitStep.ready(new AwaitStep.ReadyTab(
                    tab.tabId, tab.requestedUrl, state, tab.readinessLabel));
        } catch (BrowserException fatal) {
            gateway.closeTab(tab.handle);
            tab.state = TabState.FAILED;
            consecutiveTechnicalFailures++;
            return AwaitStep.of(AwaitStep.Outcome.PENDING); // let the caller tick again for the next tab
        }
    }

    private AwaitStep exhaustionOutcome(Batch batch) {
        boolean active = false;
        boolean anyProduced = false;
        boolean anyFailed = false;
        for (BrowserTab tab : batch.tabs) {
            switch (tab.state) {
                case QUEUED:
                case NAVIGATING:
                case LOADING:
                case READY:
                case READING:
                    active = true;
                    break;
                case COMPLETED:
                case TIMED_OUT:
                    anyProduced = true;
                    break;
                case FAILED:
                    anyFailed = true;
                    break;
                default:
                    break;
            }
        }
        if (active) {
            return AwaitStep.of(AwaitStep.Outcome.PENDING);
        }
        if (!anyProduced && anyFailed) {
            return AwaitStep.of(AwaitStep.Outcome.BATCH_FAILED);
        }
        return AwaitStep.of(AwaitStep.Outcome.DRAINED);
    }

    // ------------------------------------------------------------------ cancellation & recovery

    /** Cancel one tab (queued or loading). Terminal tabs are left untouched; late signals are ignored. */
    void closeTab(String tabId) {
        for (Batch batch : batches.values()) {
            for (BrowserTab tab : batch.tabs) {
                if (tab.tabId.equals(tabId) && !tab.state.isTerminal()) {
                    if (tab.handle != null) {
                        gateway.closeTab(tab.handle);
                    }
                    tab.state = TabState.CLOSED;
                    return;
                }
            }
        }
    }

    /** Cancel a whole batch — its queued URLs and every open page — and mark it drained. */
    void closeBatch(String batchId) {
        Batch batch = batches.get(batchId);
        if (batch == null) {
            return;
        }
        closeActiveTabs(batch);
        batch.terminal = AwaitStep.Outcome.DRAINED;
    }

    /**
     * Abort every still-active batch because a context/browser recovery invalidated all pages. Each affected
     * batch ends with the explicit terminal {@link AwaitStep.Outcome#BATCH_ABORTED_RECOVERY}. Called by the
     * {@link BrowserRecoveryCoordinator}, not decided here.
     */
    void abortAllForRecovery() {
        for (Batch batch : batches.values()) {
            if (batch.terminal != null) {
                continue;
            }
            closeActiveTabs(batch);
            batch.terminal = AwaitStep.Outcome.BATCH_ABORTED_RECOVERY;
        }
        consecutiveTechnicalFailures = 0;
    }

    /** Consecutive technical (non-timeout) tab failures — the recovery coordinator's escalation signal. */
    int consecutiveTechnicalFailures() {
        return consecutiveTechnicalFailures;
    }

    private void closeActiveTabs(Batch batch) {
        for (BrowserTab tab : batch.tabs) {
            if (!tab.state.isTerminal()) {
                if (tab.handle != null) {
                    gateway.closeTab(tab.handle);
                }
                tab.state = TabState.CLOSED;
            }
        }
    }

    // ------------------------------------------------------------------ counting helpers

    /** Count tabs across all batches: {@code holdsOpenPage} when {@code openPages} else {@code isInFlight}. */
    private int countAcross(boolean openPages) {
        int n = 0;
        for (Batch batch : batches.values()) {
            for (BrowserTab tab : batch.tabs) {
                if (openPages ? tab.state.holdsOpenPage() : tab.state.isInFlight()) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int inFlightIn(Batch batch) {
        int n = 0;
        for (BrowserTab tab : batch.tabs) {
            if (tab.state.isInFlight()) {
                n++;
            }
        }
        return n;
    }

    private static BrowserTab firstReady(Batch batch) {
        return firstInState(batch, TabState.READY);
    }

    private static BrowserTab firstInState(Batch batch, TabState state) {
        for (BrowserTab tab : batch.tabs) {
            if (tab.state == state) {
                return tab;
            }
        }
        return null;
    }
}
