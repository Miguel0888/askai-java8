package com.aresstack.askai.research.store;

import com.aresstack.askai.research.visualize.VisualizationProjection;
import com.aresstack.askai.research.visualize.VisualizationResult;
import com.aresstack.askai.research.visualize.VisualizationType;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #29: the derived visualization is persisted so the tab shows it after a restart INSTEAD of
 * regenerating. The persisted source content hash is the staleness anchor; missing/corrupt files simply mean
 * "not generated yet"; FAILED runs are not restored.
 */
public class FileVisualizationStoreTest {

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("askai-vis-store").toFile();
    }

    @Test
    public void aDiagramRoundTripsWithItsStalenessAnchor() throws Exception {
        FileVisualizationStore store = new FileVisualizationStore(tempDir());
        store.save(new VisualizationProjection("research-brief", "hash-1", "scoping",
                VisualizationResult.diagram(VisualizationType.MINDMAP, "Overview",
                        "mindmap\n  root((Thema))\n    A\n    B")));

        VisualizationProjection restored = store.load();
        assertEquals("research-brief", restored.getSourceArtifactId());
        assertEquals("the staleness anchor survives", "hash-1", restored.getSourceContentHash());
        assertEquals("scoping", restored.getPhaseId());
        assertTrue(restored.getResult().isPresent());
        assertEquals(VisualizationType.MINDMAP, restored.getResult().getType());
        assertEquals("Overview", restored.getResult().getTitle());
        assertEquals("mindmap\n  root((Thema))\n    A\n    B", restored.getResult().getMermaid());
    }

    @Test
    public void aDeliberateNoneOutcomeRoundTripsButAFailedRunIsNotRestored() throws Exception {
        FileVisualizationStore store = new FileVisualizationStore(tempDir());
        store.save(new VisualizationProjection("research-brief", "hash-2", "scoping",
                VisualizationResult.none("nothing structural yet")));
        VisualizationProjection none = store.load();
        assertEquals(VisualizationResult.Kind.NONE, none.getResult().getKind());
        assertEquals("hash-2", none.getSourceContentHash());

        store.save(new VisualizationProjection("research-brief", "hash-3", "scoping",
                VisualizationResult.failed("model unavailable")));
        assertNull("a FAILED run is not worth restoring", store.load());
    }

    @Test
    public void missingOrForeignFilesMeanNotGeneratedYet() throws Exception {
        File dir = tempDir();
        FileVisualizationStore store = new FileVisualizationStore(dir);
        assertNull(store.load());

        Files.write(new File(dir, "current.properties").toPath(),
                "schemaVersion=999\nkind=DIAGRAM\n".getBytes("UTF-8"));
        assertNull("an incompatible schema is never guessed at", store.load());
    }
}
