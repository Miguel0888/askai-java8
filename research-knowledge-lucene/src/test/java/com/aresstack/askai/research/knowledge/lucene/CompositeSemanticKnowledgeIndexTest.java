package com.aresstack.askai.research.knowledge.lucene;

import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The productive composite index: Lucene keyword search + brute-force semantic search, namespaced + rebuildable. */
public class CompositeSemanticKnowledgeIndexTest {

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-composite-index").toFile();
    }

    private static PassageIndexDocument doc(String passageId, String captureId, String fp, String text,
                                            float[] v) {
        return new PassageIndexDocument(passageId, captureId, "src-" + captureId, text, "Root", "seg-v1", fp, v);
    }

    private static SemanticKnowledgeIndex indexWith(File dir) {
        CompositeSemanticKnowledgeIndex index = new CompositeSemanticKnowledgeIndex(dir, "p1");
        index.indexPassages("p1", Arrays.asList(
                doc("p-glasses", "cap-1", "fpA", "smart glasses project images onto the eye",
                        new float[]{1f, 0f, 0f}),
                doc("p-battery", "cap-2", "fpA", "battery life is very limited today",
                        new float[]{0f, 1f, 0f})));
        return index;
    }

    @Test
    public void keywordSearchFindsThePassageByText() throws IOException {
        SemanticKnowledgeIndex index = indexWith(tempDir());
        List<PassageSearchHit> hits = index.keywordSearch("p1", new PassageTextQuery("fpA", "glasses", 10));
        assertEquals(1, hits.size());
        assertEquals("p-glasses", hits.get(0).getPassageId());
        assertEquals("src-cap-1", hits.get(0).getSourceId());
    }

    @Test
    public void semanticSearchRanksTheCloserPassageHigher() throws IOException {
        SemanticKnowledgeIndex index = indexWith(tempDir());
        List<PassageSearchHit> hits = index.semanticSearch("p1",
                new PassageSemanticQuery("fpA", new float[]{9f, 1f, 0f}, 10));
        assertEquals(2, hits.size());
        assertEquals("p-glasses", hits.get(0).getPassageId());
        assertTrue(hits.get(0).getScore() > hits.get(1).getScore());
    }

    @Test
    public void differentFingerprintsAreDistinctNamespaces() throws IOException {
        File dir = tempDir();
        SemanticKnowledgeIndex index = indexWith(dir);
        index.indexPassages("p1", Arrays.asList(
                doc("p-other", "cap-9", "fpB", "smart glasses in another embedding world",
                        new float[]{1f, 0f, 0f, 0f})));
        // The fpA keyword search never returns the fpB passage.
        List<PassageSearchHit> hits = index.keywordSearch("p1", new PassageTextQuery("fpA", "glasses", 10));
        assertEquals(1, hits.size());
        assertEquals("p-glasses", hits.get(0).getPassageId());
    }

    @Test
    public void reIndexingDoesNotDuplicateAndReplaceSupersedesACapture() throws IOException {
        File dir = tempDir();
        SemanticKnowledgeIndex index = indexWith(dir);
        // Re-index the same passage: no duplicate.
        index.indexPassages("p1", Arrays.asList(
                doc("p-glasses", "cap-1", "fpA", "smart glasses project images onto the eye",
                        new float[]{1f, 0f, 0f})));
        assertEquals(1, index.keywordSearch("p1", new PassageTextQuery("fpA", "glasses", 10)).size());

        // A new generation of cap-1 supersedes the old passage.
        index.replacePassagesForCapture("p1", "fpA", "cap-1", Arrays.asList(
                doc("p-glasses-v2", "cap-1", "fpA", "augmented reality spectacles overlay text",
                        new float[]{0f, 0f, 1f})));
        assertTrue("old passage superseded",
                index.keywordSearch("p1", new PassageTextQuery("fpA", "glasses", 10)).isEmpty());
        assertEquals(1, index.keywordSearch("p1", new PassageTextQuery("fpA", "spectacles", 10)).size());
    }

    @Test
    public void rebuildReproducesTheIndexAndAnEmptiedIndexCanBeRebuilt() throws IOException {
        File dir = tempDir();
        SemanticKnowledgeIndex index = indexWith(dir);
        index.removeProject("p1");
        assertTrue(index.keywordSearch("p1", new PassageTextQuery("fpA", "glasses", 10)).isEmpty());
        assertTrue(index.semanticSearch("p1",
                new PassageSemanticQuery("fpA", new float[]{1f, 0f, 0f}, 10)).isEmpty());

        index.rebuild("p1", Arrays.asList(
                doc("p-glasses", "cap-1", "fpA", "smart glasses project images onto the eye",
                        new float[]{1f, 0f, 0f})));
        assertEquals(1, index.keywordSearch("p1", new PassageTextQuery("fpA", "glasses", 10)).size());
        assertEquals(1, index.semanticSearch("p1",
                new PassageSemanticQuery("fpA", new float[]{1f, 0f, 0f}, 10)).size());
    }
}
