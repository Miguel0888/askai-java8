package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The productive PassageStore records a capture's sentences + passages through the file-backed
 * ResearchProjectRepository so they survive a reload; re-storing the same derivation is idempotent (one
 * commit); a second capture is stored without disturbing the first.
 */
public class ResearchProjectPassageStoreTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-passage-store").toFile();
    }

    private static SourceCapture capture(String captureId, String url) {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one. Beta two.");
        return new SourceCapture(captureId, "src-1", url, 7L, "h-" + captureId, "T", "A",
                Collections.singletonList(b));
    }

    private static List<Sentence> sentences(String captureId) {
        return Arrays.asList(
                new Sentence(captureId + "#s0", captureId, "b1", 0, "Alpha one."),
                new Sentence(captureId + "#s1", captureId, "b1", 1, "Beta two."));
    }

    private static List<Passage> passages(String captureId) {
        return Arrays.asList(new Passage(captureId + "#p0@seg-v1-fpA", captureId,
                Arrays.asList(captureId + "#s0", captureId + "#s1"), "Root", "Alpha one. Beta two.",
                "fpA", "seg-v1"));
    }

    @Test
    public void storesACaptureGenerationThatSurvivesAReload() throws IOException {
        File dir = tempDir();
        new ResearchProjectPassageStore(new FileResearchProjectRepository(dir), "p1")
                .store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, reloaded.captures().size());
        assertEquals(2, reloaded.sentences().size());
        assertEquals(1, reloaded.passages().size());
        Passage p = reloaded.passages().get("cap-1#p0@seg-v1-fpA");
        assertNotNull(p);
        assertEquals(Arrays.asList("cap-1#s0", "cap-1#s1"), p.getSentenceIds());
    }

    @Test
    public void reStoringTheSameDerivationIsIdempotent() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store =
                new ResearchProjectPassageStore(new FileResearchProjectRepository(dir), "p1");
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"));
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, reloaded.captures().size());
        assertEquals(1, reloaded.passages().size());
    }

    @Test
    public void storingASecondCaptureLeavesTheFirstIntact() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store =
                new ResearchProjectPassageStore(new FileResearchProjectRepository(dir), "p1");
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"));
        store.store(capture("cap-2", "https://x/2"), sentences("cap-2"), passages("cap-2"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(2, reloaded.captures().size());
        assertEquals(4, reloaded.sentences().size());
        assertNotNull("the first capture's passage is still active",
                reloaded.passages().get("cap-1#p0@seg-v1-fpA"));
        assertNotNull("the second capture's passage is active",
                reloaded.passages().get("cap-2#p0@seg-v1-fpA"));
    }
}
