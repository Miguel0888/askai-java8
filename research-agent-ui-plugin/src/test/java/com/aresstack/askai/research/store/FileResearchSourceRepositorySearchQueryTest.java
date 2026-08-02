package com.aresstack.askai.research.store;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * The user web-search query that found a source survives a write/read round-trip, so "what was already
 * searched" is restorable after an app restart.
 */
public class FileResearchSourceRepositorySearchQueryTest {

    @Test
    public void searchQueryPersistsAcrossReload() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-sources-test").toFile();
        FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
        repo.put(ResearchSourceRecord.builder("source-1")
                .title("Wearables market 2024")
                .url("https://example.org/a")
                .searchQuery("wearables audio video")
                .revision(1L)
                .build());

        // A fresh repository over the SAME directory reads the persisted query back (restart-safe).
        FileResearchSourceRepository reloaded = new FileResearchSourceRepository(dir);
        assertEquals("wearables audio video", reloaded.get("source-1").getSearchQuery());
        assertEquals("wearables audio video",
                reloaded.find(SourceQuery.all()).get(0).getSearchQuery());
    }

    @Test
    public void anOldFileWithoutTheKeyReadsAsEmpty() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-sources-test").toFile();
        FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
        // A record with no searchQuery (agent-accepted / pre-migration) round-trips as "".
        repo.put(ResearchSourceRecord.builder("source-2").title("t").revision(1L).build());
        assertEquals("", new FileResearchSourceRepository(dir).get("source-2").getSearchQuery());
    }
}
