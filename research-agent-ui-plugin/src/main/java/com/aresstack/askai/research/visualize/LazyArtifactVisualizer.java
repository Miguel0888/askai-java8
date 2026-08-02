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
 * after a short idle debounce AND only when the main agent is not busy (else it defers AND RE-ARMS, so it is
 * never forgotten). The CONTENT HASH is the authority — a result computed for an older hash is discarded when
 * a newer artifact has arrived, so nothing flickers between stale and fresh diagrams. It has no workflow
 * authority: it only produces a derived {@link VisualizationProjection} + a {@link VisualizationStatus} through
 * the supplied sinks. Every step is traced via {@link VisualizerDiagnostics}.
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
        ArtifactSnapshot snapshot = desired;
        if (snapshot == null) {
            return;
        }
        if (agentBusy.getAsBoolean()) {
            VisualizerDiagnostics.log("deferred busy=true hash="
                    + VisualizerDiagnostics.shortHash(snapshot.getContentHash()));
            arm(); // defer AND re-arm — the main agent is working; try again after the next idle window
            return;
        }
        runOnce(snapshot);
    }

    /** Visualize the snapshot and publish ONLY if it is still the desired content (else discard as stale). */
    void runOnce(ArtifactSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String startedHash = snapshot.getContentHash();
        VisualizerDiagnostics.log("started hash=" + VisualizerDiagnostics.shortHash(startedHash));
        statusSink.accept(VisualizationStatus.RUNNING);
        VisualizationResult result = service.visualize(snapshot);
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
        scheduler.shutdownNow();
    }
}
