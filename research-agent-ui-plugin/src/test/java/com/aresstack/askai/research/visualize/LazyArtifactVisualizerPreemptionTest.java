package com.aresstack.askai.research.visualize;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The low-priority visualizer is PREEMPTIBLE: when the foreground agent takes the shared model, an in-flight
 * visualize is aborted and its (aborted) result is never published; the dirty artifact is kept and, once the
 * foreground agent is idle again, the LATEST artifact is visualized for real.
 */
public class LazyArtifactVisualizerPreemptionTest {

    private static final long SHORT_DEBOUNCE = 10L;

    @Test
    public void preemptionAbortsTheRunDiscardsItsResultThenRetriesWhenIdle() throws Exception {
        final CountDownLatch firstRunStarted = new CountDownLatch(1);
        final AtomicBoolean firstRunInterrupted = new AtomicBoolean(false);
        final AtomicInteger calls = new AtomicInteger(0);
        // First visualize blocks (a long inference) until interrupted; any later one returns a diagram at once,
        // exactly the way ModelArtifactVisualizer aborts on interrupt and succeeds when it runs uncontended.
        ArtifactVisualizationService service = new ArtifactVisualizationService() {
            public VisualizationResult visualize(ArtifactSnapshot snapshot) {
                if (calls.incrementAndGet() == 1) {
                    firstRunStarted.countDown();
                    try {
                        Thread.sleep(60_000L);
                    } catch (InterruptedException interrupted) {
                        firstRunInterrupted.set(true);
                        return VisualizationResult.failed("interrupted");
                    }
                }
                return VisualizationResult.diagram(VisualizationType.GRAPH, "t", "graph LR\n A --> B");
            }
        };

        final AtomicReference<VisualizationProjection> published =
                new AtomicReference<VisualizationProjection>();
        final AtomicBoolean foregroundBusy = new AtomicBoolean(false);
        final AtomicReference<VisualizationStatus> status = new AtomicReference<VisualizationStatus>();
        LazyArtifactVisualizer visualizer = new LazyArtifactVisualizer(service,
                new java.util.function.BooleanSupplier() {
                    public boolean getAsBoolean() {
                        return foregroundBusy.get();
                    }
                },
                new java.util.function.Consumer<VisualizationProjection>() {
                    public void accept(VisualizationProjection projection) {
                        published.set(projection);
                    }
                },
                new java.util.function.Consumer<VisualizationStatus>() {
                    public void accept(VisualizationStatus s) {
                        status.set(s);
                    }
                }, SHORT_DEBOUNCE);

        ArtifactSnapshot brief = new ArtifactSnapshot("research-brief", "Brief A", "scoping");
        visualizer.onArtifactChanged(brief);
        assertTrue("the visualize worker started the (blocking) inference",
                firstRunStarted.await(2, TimeUnit.SECONDS));

        // The foreground agent takes the model and preempts the visualizer mid-run.
        foregroundBusy.set(true);
        visualizer.preempt();
        Thread.sleep(300L);
        assertTrue("the in-flight inference was aborted", firstRunInterrupted.get());
        assertNull("a preempted run publishes nothing", published.get());
        assertEquals("the visualizer is dirty again, ready to retry", VisualizationStatus.PREPARING,
                status.get());

        // Foreground agent goes idle → the kept dirty artifact is visualized for real.
        foregroundBusy.set(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (published.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertNotNull("the latest artifact is visualized once the foreground agent is idle", published.get());
        assertEquals(brief.getContentHash(), published.get().getSourceContentHash());
        assertEquals(VisualizationStatus.HAS_DIAGRAM, status.get());

        visualizer.shutdown();
    }
}
