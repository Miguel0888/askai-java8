package com.aresstack.askai.research.store;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The parked-source fields (search excerpt, page full text, reranker score) survive a write/read round-trip,
 * so a source parked before a visit and enriched after a visit is restorable across an app restart.
 */
public class FileResearchSourceRepositoryFullTextTest {

    @Test
    public void excerptFullTextAndScorePersistAcrossReload() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-sources-fulltext").toFile();
        FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
        // Multi-line full text with CR and LF must round-trip untruncated.
        String body = "Line one.\nLine two with a carriage return\r\nand a backslash \\ and =equals: colon.";
        repo.put(ResearchSourceRecord.builder("source-1")
                .title("Smart glasses displays")
                .url("https://example.org/a")
                .excerpt("A short search-result snippet about waveguide displays.")
                .fullText(body)
                .rerankScore(1.2345)
                .status(SourceStatus.NEW)
                .revision(1L)
                .build());

        ResearchSourceRecord back = new FileResearchSourceRepository(dir).get("source-1");
        assertEquals("A short search-result snippet about waveguide displays.", back.getExcerpt());
        assertEquals(body, back.getFullText());
        assertEquals(1.2345, back.getRerankScore(), 1e-9);
        assertTrue(back.hasRerankScore());
        assertFalse(back.isParked());
    }

    @Test
    public void aParkedSourceHasScoreButEmptyFullText() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-sources-parked").toFile();
        FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
        repo.put(ResearchSourceRecord.builder("source-2")
                .title("Parked candidate")
                .url("https://example.org/b")
                .excerpt("Snippet only, page not yet visited.")
                .rerankScore(0.75)
                .status(SourceStatus.PARKED)
                .revision(1L)
                .build());

        ResearchSourceRecord back = new FileResearchSourceRepository(dir).get("source-2");
        assertTrue(back.isParked());
        assertEquals("", back.getFullText());
        assertEquals(0.75, back.getRerankScore(), 1e-9);
        assertEquals(SourceStatus.PARKED, back.getStatus());
    }

    @Test
    public void anOldFileWithoutTheNewKeysReadsAsEmptyAndNoScore() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-sources-legacy").toFile();
        FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
        // A record built without the new fields (pre-migration) round-trips as ""/"" and NaN.
        repo.put(ResearchSourceRecord.builder("source-3").title("t").revision(1L).build());

        ResearchSourceRecord back = new FileResearchSourceRepository(dir).get("source-3");
        assertEquals("", back.getExcerpt());
        assertEquals("", back.getFullText());
        assertFalse(back.hasRerankScore());
        assertTrue(Double.isNaN(back.getRerankScore()));
        assertTrue(back.isParked());
    }
}
