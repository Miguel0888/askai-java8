package com.aresstack.askai.research.visualize;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The LOW-PRIORITY, PREEMPTIBLE artifact-visualizer agent. An artifact change marks work dirty, but the
 * visualizer only starts after a short idle debounce AND only when the foreground research agent is not busy
 * (else it defers AND RE-ARMS, so it is never forgotten). Once started it runs on its OWN worker thread, so
 * when the foreground agent takes the (shared, serial) model it is {@link #preempt() preempted}: the in-flight
 * inference is cancelled, the dirty target is kept, and the debounce re-arms to retry the LATEST artifact once
 * the model is free again. The CONTENT HASH plus a run GENERATION are the authority — a result computed for an
 * older hash or for a preempted run is discarded, so nothing flickers between stale/aborted and fresh diagrams.
 * It has no workflow authority: it only produces a derived {@link VisualizationProjection} + a
 * {@link VisualizationStatus} through the supplied sinks. Every step is traced via {@link VisualizerDiagnostics}.
 */
public final class LazyArtifactVisualizer {

    /** Idle time after the last artifact change before visualizing (named, not a scattered magic number). */
    public static final long DEBOUNCE_MILLIS = 1500L;

    private final ArtifactVisualizationService service;
    private final BooleanSupplier agentBusy;
    private final Consumer<VisualizationProjection> resultSink;
    private final Consumer<VisualizationStatus> statusSink;
    private final long debounceMillis;
    private final ScheduledExecutorService scheduler;

    private volatile ArtifactSnapshot desired;
    private volatile String desiredHash = "";
    private ScheduledFuture<?> pending;
    // The worker running the current visualize (interrupted on preemption), or null when idle. The generation
    // invalidates a run whose result must be discarded: it is bumped both when a new run starts and on preempt,
    // so a completed/aborted inference is only published when it is still the newest, un-preempted run.
    private volatile Thread runningThread;
    private final AtomicInteger generation = new AtomicInteger();

    public LazyArtifactVisualizer(ArtifactVisualizationService service, BooleanSupplier agentBusy,
                                  Consumer<VisualizationProjection> resultSink,
                                  Consumer<VisualizationStatus> statusSink) {
        this(service, agentBusy, resultSink, statusSink, DEBOUNCE_MILLIS);
    }

    LazyArtifactVisualizer(ArtifactVisualizationService service, BooleanSupplier agentBusy,
                           Consumer<VisualizationProjection> resultSink,
                           Consumer<VisualizationStatus> statusSink, long debounceMillis) {
        this.service = service;
        this.agentBusy = agentBusy;
        this.resultSink = resultSink;
        this.statusSink = statusSink;
        this.debounceMillis = debounceMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "artifact-visualizer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** The artifact changed: record it as the target and (re)arm the debounce. */
    public void onArtifactChanged(ArtifactSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.desired = snapshot;
        this.desiredHash = snapshot.getContentHash();
        VisualizerDiagnostics.log("scheduled hash=" + VisualizerDiagnostics.shortHash(desiredHash)
                + " debounceMs=" + debounceMillis);
        statusSink.accept(VisualizationStatus.PREPARING);
        arm();
    }

    /**
     * The foreground agent is taking the (shared, serial) model: yield it NOW. Any in-flight visualize is
     * cancelled (its inference is aborted via the worker's interrupt) and its result invalidated, the dirty
     * target is KEPT, and the debounce re-arms so the LATEST artifact is visualized once the model is free
     * again. Safe to call at any time, from any thread, whether or not a visualize is currently running.
     */
    public synchronized void preempt() {
        Thread running = runningThread;
        if (running != null && running.isAlive()) {
            generation.incrementAndGet(); // invalidate the in-flight run — its result must not be published
            VisualizerDiagnostics.log("preempted hash=" + VisualizerDiagnostics.shortHash(desiredHash));
            statusSink.accept(VisualizationStatus.PREPARING); // still dirty; it will retry after idle
            running.interrupt(); // aborts the blocking inference (ModelArtifactVisualizer cancels the port)
        }
        arm(); // retry the newest desired artifact once the foreground agent has gone idle again
    }

    private synchronized void arm() {
        if (pending != null) {
            pending.cancel(false); // never interrupt an in-flight visualize; the hash/generation checks apply
        }
        pending = scheduler.schedule(new Runnable() {
            public void run() {
                tick();
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        ArtifactSnapshot snapshot = desired;
        if (snapshot == null) {
            return;
        }
        if (agentBusy.getAsBoolean()) {
            VisualizerDiagnostics.log("deferred busy=true hash="
                    + VisualizerDiagnostics.shortHash(snapshot.getContentHash()));
            arm(); // defer AND re-arm — the foreground agent is working; try again after the next idle window
            return;
        }
        synchronized (this) {
            Thread running = runningThread;
            if (running != null && running.isAlive()) {
                arm(); // a visualize is still running; re-arm to pick up the newest artifact once it finishes
                return;
            }
            startWorker(snapshot);
        }
    }

    /** Launch the visualize on its OWN daemon worker so a foreground turn can preempt it by interrupt. */
    private void startWorker(final ArtifactSnapshot snapshot) {
        final int gen = generation.incrementAndGet();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    runGuarded(snapshot, gen);
                } finally {
                    // Clear the reference only if we are still the current run (a preempt may have already
                    // moved on and started nothing new — leaving it null either way is correct).
                    synchronized (LazyArtifactVisualizer.this) {
                        if (runningThread == Thread.currentThread()) {
                            runningThread = null;
                        }
                    }
                }
            }
        }, "artifact-visualizer-run");
        worker.setDaemon(true);
        runningThread = worker;
        worker.start();
    }

    /** Visualize the snapshot and publish ONLY if it is still the desired content (else discard as stale). */
    void runOnce(ArtifactSnapshot snapshot) {
        // The synchronous entry point (used by tests): guard against the CURRENT generation, which the direct
        // caller never bumps — so a fresh, un-preempted call always publishes when the hash still matches.
        runGuarded(snapshot, generation.get());
    }

    private void runGuarded(ArtifactSnapshot snapshot, int gen) {
        if (snapshot == null) {
            return;
        }
        String startedHash = snapshot.getContentHash();
        VisualizerDiagnostics.log("started hash=" + VisualizerDiagnostics.shortHash(startedHash));
        statusSink.accept(VisualizationStatus.RUNNING);
        VisualizationResult result = service.visualize(snapshot);
        if (gen != generation.get()) {
            VisualizerDiagnostics.log("preempted-discard hash=" + VisualizerDiagnostics.shortHash(startedHash));
            return; // this run was preempted (or superseded); its aborted result is not shown, dirty stays set
        }
        if (!startedHash.equals(desiredHash)) {
            VisualizerDiagnostics.log("stale expected=" + VisualizerDiagnostics.shortHash(startedHash)
                    + " actual=" + VisualizerDiagnostics.shortHash(desiredHash));
            return; // a newer artifact arrived while we ran — discard; the newer one has its own scheduled run
        }
        switch (result.getKind()) {
            case DIAGRAM:
                VisualizerDiagnostics.log("result=DIAGRAM chars=" + result.getMermaid().length());
                statusSink.accept(VisualizationStatus.HAS_DIAGRAM);
                break;
            case NONE:
                VisualizerDiagnostics.log("result=NONE");
                statusSink.accept(VisualizationStatus.NONE_DECIDED);
                break;
            case FAILED:
            default:
                VisualizerDiagnostics.log("result=FAILED reason=" + result.getReason());
                statusSink.accept(VisualizationStatus.FAILED);
                break;
        }
        resultSink.accept(new VisualizationProjection(
                snapshot.getArtifactId(), startedHash, snapshot.getPhaseId(), result));
    }

    public void shutdown() {
        Thread running = runningThread;
        if (running != null) {
            running.interrupt(); // abort any in-flight inference on close so the model is freed promptly
        }
        scheduler.shutdownNow();
    }
}
