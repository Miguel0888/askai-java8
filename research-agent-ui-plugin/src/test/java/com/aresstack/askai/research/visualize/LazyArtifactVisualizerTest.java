package com.aresstack.askai.research.visualize;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/** The content hash is the authority: a result computed for an outdated artifact is discarded, not shown. */
public class LazyArtifactVisualizerTest {

    private static final long NEVER = 3_600_000L; // huge debounce so the internal scheduler never interferes

    private final ArtifactVisualizationService alwaysDiagram = new ArtifactVisualizationService() {
        public VisualizationResult visualize(ArtifactSnapshot snapshot) {
            return VisualizationResult.diagram(VisualizationType.GRAPH, "t", "graph LR\n A --> B");
        }
    };

    @Test
    public void aResultForAnOutdatedArtifactIsDiscardedAndTheCurrentOneIsPublished() {
        final AtomicReference<VisualizationProjection> published =
                new AtomicReference<VisualizationProjection>();
        LazyArtifactVisualizer visualizer = new LazyArtifactVisualizer(alwaysDiagram,
                new java.util.function.BooleanSupplier() {
                    public boolean getAsBoolean() {
                        return false;
                    }
                },
                new java.util.function.Consumer<VisualizationProjection>() {
                    public void accept(VisualizationProjection projection) {
                        published.set(projection);
                    }
                }, NEVER);

        ArtifactSnapshot older = new ArtifactSnapshot("research-brief", "Brief A", "scoping");
        ArtifactSnapshot newer = new ArtifactSnapshot("research-brief", "Brief B", "scoping");
        visualizer.onArtifactChanged(older);
        visualizer.onArtifactChanged(newer); // the desired content is now B

        visualizer.runOnce(older); // computed for A, but B is desired -> discarded
        assertNull("a stale result is never published", published.get());

        visualizer.runOnce(newer); // matches the desired hash -> published
        assertNotNull(published.get());
        assertEquals(newer.getContentHash(), published.get().getSourceContentHash());
        assertEquals("research-brief", published.get().getSourceArtifactId());

        visualizer.shutdown();
    }
}
