package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The file-backed ResearchProjectRepository: per-entity layout, atomic writes, schema version, roundtrip. */
public class FileResearchProjectRepositoryTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-rproj").toFile();
    }

    private static SourceCapture capture(String id, String checksum) {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one.\nAlpha two.");
        return new SourceCapture(id, "src-1", "https://x/" + id, 7L, checksum, "T", "A",
                Collections.singletonList(b));
    }

    /** A project with one capture + its sentences + passages (deterministic ids). */
    private static ResearchProject sampleProject() {
        ResearchProject p = new ResearchProject("p1", IdSequence.counting());
        p.recordSourceCapture(capture("cap-1", "h1"));
        p.recordSentences(Arrays.asList(
                new Sentence("cap-1#s0", "cap-1", "b1", 0, "Alpha one."),
                new Sentence("cap-1#s1", "cap-1", "b1", 1, "Alpha two.")));
        p.recordPassages(Arrays.asList(new Passage("cap-1#p0@seg-v1-fp", "cap-1",
                Arrays.asList("cap-1#s0", "cap-1#s1"), "Root", "Alpha one. Alpha two.", "fp")));
        return p;
    }

    @Test
    public void emptyProjectLoadsCleanly() throws IOException {
        ResearchProject p = new FileResearchProjectRepository(tempDir()).load("p1");
        assertEquals("p1", p.getProjectId());
        assertTrue(p.captures().isEmpty());
        assertTrue(p.sentences().isEmpty());
        assertTrue(p.passages().isEmpty());
    }

    @Test
    public void captureSentencesAndPassagesSurviveARestartWithIdsAndRefs() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(sampleProject());

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, reloaded.captures().size());
        SourceCapture c = reloaded.captures().get("cap-1");
        assertNotNull(c);
        assertEquals("src-1", c.getSourceId());
        assertEquals("h1", c.getChecksum());
        assertEquals(1, c.getBlocks().size());
        assertEquals(SourceCapture.BlockKind.PARAGRAPH, c.getBlocks().get(0).getKind());
        assertEquals("Alpha one.\nAlpha two.", c.getBlocks().get(0).getText());

        assertEquals(2, reloaded.sentences().size());
        assertEquals("Alpha two.", reloaded.sentences().get("cap-1#s1").getText());
        assertEquals(0, reloaded.sentences().get("cap-1#s0").getOrdinal());
        assertEquals(1, reloaded.sentences().get("cap-1#s1").getOrdinal());

        assertEquals(1, reloaded.passages().size());
        Passage p = reloaded.passages().get("cap-1#p0@seg-v1-fp");
        assertNotNull(p);
        assertEquals("cap-1", p.getCaptureId());
        assertEquals(Arrays.asList("cap-1#s0", "cap-1#s1"), p.getSentenceIds());
        assertEquals("fp", p.getEmbeddingFingerprint());
    }

    @Test
    public void savingTheSameProjectAgainIsIdempotentAndLeavesNoTempFiles() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        repo.save(sampleProject());
        repo.save(sampleProject()); // unchanged content → writeIfChanged skips; no partial/temp files
        assertTrue("no leftover .tmp files", tempFiles(new File(dir, "knowledge")).isEmpty());
        ResearchProject reloaded = repo.load("p1");
        assertEquals(1, reloaded.captures().size());
        assertEquals(2, reloaded.sentences().size());
        assertEquals(1, reloaded.passages().size());
    }

    @Test
    public void multipleCapturesOfTheSameSourceStaySeparate() throws IOException {
        File dir = tempDir();
        ResearchProject p = new ResearchProject("p1", IdSequence.counting());
        p.recordSourceCapture(capture("cap-1", "h1"));
        p.recordSourceCapture(capture("cap-2", "h2")); // same source, different version
        new FileResearchProjectRepository(dir).save(p);

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(2, reloaded.captures().size());
        assertNotNull(reloaded.captures().get("cap-1"));
        assertNotNull(reloaded.captures().get("cap-2"));
    }

    @Test
    public void anUnknownSchemaVersionIsRejectedNotGuessed() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(sampleProject());
        // Corrupt the version marker to a future/unknown value.
        File meta = new File(new File(dir, "knowledge"), "project.properties");
        Files.write(meta.toPath(), "schemaVersion=999\nprojectId=p1\n".getBytes(Charset.forName("UTF-8")));
        try {
            new FileResearchProjectRepository(dir).load("p1");
            fail("an incompatible schema version must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("schema version"));
        }
    }

    private static List<File> tempFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) {
                    out.addAll(tempFiles(f));
                } else if (f.getName().endsWith(".tmp")) {
                    out.add(f);
                }
            }
        }
        return out;
    }
}
