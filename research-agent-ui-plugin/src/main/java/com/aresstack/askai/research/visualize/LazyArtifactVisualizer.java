package com.aresstack.askai.research.visualize;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs artifact visualization LAZILY: an artifact change marks work dirty, but the visualizer only starts
 * after a short idle debounce AND only when the main agent is not busy (else it defers). The CONTENT HASH is
 * the authority — a result computed for an older hash is discarded when a newer artifact has arrived, so
 * nothing flickers between stale and fresh diagrams. It has no workflow authority: it only produces a derived
 * {@link VisualizationProjection} through the supplied sink; it never touches the artifact or the workflow.
 */
public final class LazyArtifactVisualizer {

    /** Idle time after the last artifact change before visualizing (named, not a scattered magic number). */
    public static final long DEBOUNCE_MILLIS = 1500L;

    private final ArtifactVisualizationService service;
    private final BooleanSupplier agentBusy;
    private final Consumer<VisualizationProjection> sink;
    private final long debounceMillis;
    private final ScheduledExecutorService scheduler;

    private volatile ArtifactSnapshot desired;
    private volatile String desiredHash = "";
    private ScheduledFuture<?> pending;

    public LazyArtifactVisualizer(ArtifactVisualizationService service, BooleanSupplier agentBusy,
                                  Consumer<VisualizationProjection> sink) {
        this(service, agentBusy, sink, DEBOUNCE_MILLIS);
    }

    LazyArtifactVisualizer(ArtifactVisualizationService service, BooleanSupplier agentBusy,
                           Consumer<VisualizationProjection> sink, long debounceMillis) {
        this.service = service;
        this.agentBusy = agentBusy;
        this.sink = sink;
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
        arm();
    }

    private synchronized void arm() {
        if (pending != null) {
            pending.cancel(false); // never interrupt an in-flight visualize; the hash check handles staleness
        }
        pending = scheduler.schedule(new Runnable() {
            public void run() {
                tick();
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (agentBusy.getAsBoolean()) {
            arm(); // the main agent is working — defer, do not compete for the model
            return;
        }
        runOnce(desired);
    }

    /** Visualize the snapshot and publish ONLY if it is still the desired content (else discard as stale). */
    void runOnce(ArtifactSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String startedHash = snapshot.getContentHash();
        VisualizationResult result = service.visualize(snapshot);
        if (!startedHash.equals(desiredHash)) {
            return; // a newer artifact arrived while we ran — discard; the newer one has its own scheduled run
        }
        sink.accept(new VisualizationProjection(
                snapshot.getArtifactId(), startedHash, snapshot.getPhaseId(), result));
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
