package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The productive resume source reconstructs index docs (with sourceId + vector) from the canonical persistence. */
public class RepositoryIndexableGenerationSourceTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-resume-src").toFile();
    }

    private static void persistGeneration(File dir) {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one. Beta two.");
        SourceCapture capture = new SourceCapture("cap-1", "source-7", "https://x/1", 7L, "h", "T", "A",
                Collections.singletonList(b));
        List<Sentence> sentences = Arrays.asList(new Sentence("cap-1#s0", "cap-1", "b1", 0, "Alpha one."));
        List<Passage> passages = Arrays.asList(new Passage("cap-1#p0@seg-v1-fpA", "cap-1",
                Arrays.asList("cap-1#s0"), "Root", "Alpha one.", "fpA", "seg-v1"));
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        vectors.put("cap-1#p0@seg-v1-fpA", new EmbeddingPort.EmbeddingVector("m", "fpA",
                new float[]{0.5f, 0.5f, 0f}));
        new ResearchProjectPassageStore(new FileResearchProjectRepository(dir), "p1",
                new FilePassageVectorStore(dir)).store(capture, sentences, passages, vectors);
    }

    @Test
    public void reconstructsIndexDocumentsFromCanonicalPersistence() throws IOException {
        File dir = tempDir();
        persistGeneration(dir);

        RepositoryIndexableGenerationSource source = new RepositoryIndexableGenerationSource(
                new FileResearchProjectRepository(dir), new FilePassageVectorStore(dir), "p1");
        List<PassageIndexDocument> docs = source.loadPersisted("cap-1", "seg-v1", "fpA");

        assertEquals(1, docs.size());
        PassageIndexDocument d = docs.get(0);
        assertEquals("cap-1#p0@seg-v1-fpA", d.getPassageId());
        assertEquals("source-7", d.getSourceId());
        assertEquals("Alpha one.", d.getText());
        assertArrayEquals(new float[]{0.5f, 0.5f, 0f}, d.getEmbedding(), 1e-6f);
    }

    @Test
    public void anUnpersistedGenerationIsEmpty() throws IOException {
        File dir = tempDir();
        persistGeneration(dir);
        RepositoryIndexableGenerationSource source = new RepositoryIndexableGenerationSource(
                new FileResearchProjectRepository(dir), new FilePassageVectorStore(dir), "p1");
        // A different embedding world was never persisted → empty (the worker then runs the full pipeline).
        assertTrue(source.loadPersisted("cap-1", "seg-v1", "fpZZ").isEmpty());
    }
}
