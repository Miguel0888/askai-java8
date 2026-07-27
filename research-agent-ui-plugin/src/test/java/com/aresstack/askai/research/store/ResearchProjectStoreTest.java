package com.aresstack.askai.research.store;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** File-backed project persistence: atomic writes, revisions/checksums, restart restore, corruption isolation. */
public class ResearchProjectStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void createProjectWriteMarkdownAndReloadAcrossRestart() throws Exception {
        File root = folder.newFolder("project");
        ResearchProjectStore store = new ResearchProjectStore(root);
        ArtifactWriteResult w = store.artifacts().replace("outline", 0L, "# Outline\n");
        assertTrue(w.isSuccess());
        assertEquals(1L, w.getRevision());
        // The markdown is a real file at a relative path.
        assertTrue(new File(root, "artifacts/outline.md").isFile());

        // "Restart": a fresh store over the same root restores content + revision.
        ResearchProjectStore restarted = new ResearchProjectStore(root);
        ArtifactContent restored = restarted.artifacts().read("outline");
        assertEquals("# Outline\n", restored.getMarkdown());
        assertEquals(1L, restored.getRevision());
    }

    @Test
    public void artifactRevisionConflictDoesNotOverwrite() {
        ResearchProjectStore store = new ResearchProjectStore(tmp());
        store.artifacts().replace("draft", 0L, "v1"); // -> rev 1
        ArtifactWriteResult stale = store.artifacts().replace("draft", 0L, "v2-from-old-copy");
        assertFalse(stale.isSuccess());
        assertEquals("v1", store.artifacts().read("draft").getMarkdown());
    }

    @Test
    public void missingArtifactReadsEmptyAtRevisionZero() {
        ResearchProjectStore store = new ResearchProjectStore(tmp());
        ArtifactContent c = store.artifacts().read("nope");
        assertEquals("", c.getMarkdown());
        assertEquals(0L, c.getRevision());
    }

    @Test
    public void corruptArtifactMetaKeepsMarkdown() throws Exception {
        File root = folder.newFolder("project");
        ResearchProjectStore store = new ResearchProjectStore(root);
        store.artifacts().replace("final", 0L, "# Final\n");
        // Corrupt the meta file.
        StoreIo.atomicWrite(new File(root, "artifacts/final.md.meta"), "revision=not-a-number\n");
        ArtifactContent c = store.artifacts().read("final");
        assertEquals("# Final\n", c.getMarkdown()); // markdown preserved
        assertEquals(1L, c.getRevision());          // revision falls back, isolated
    }

    @Test
    public void sourceWriteReloadRevisionAndConflict() throws Exception {
        File root = folder.newFolder("project");
        ResearchProjectStore store = new ResearchProjectStore(root);
        store.sources().put(ResearchSourceRecord.builder("src1")
                .title("PF4J").origin("github").status(SourceStatus.NEW)
                .linkedSectionIds(Arrays.asList("s1")).revision(1L).build());

        // Restart: reload the record.
        ResearchProjectStore restarted = new ResearchProjectStore(root);
        ResearchSourceRecord loaded = restarted.sources().get("src1");
        assertEquals("PF4J", loaded.getTitle());
        assertTrue(loaded.getLinkedSectionIds().contains("s1"));

        com.aresstack.askai.research.sources.SourceUpdateResult ok = restarted.sources().update("src1", 1L,
                SourceUpdate.from(loaded).status(SourceStatus.ACCEPTED).build());
        assertTrue(ok.isSuccess());
        assertEquals(2L, ok.getRecord().getRevision());
        // Stale write rejected, no overwrite.
        com.aresstack.askai.research.sources.SourceUpdateResult stale =
                restarted.sources().update("src1", 1L, SourceUpdate.from(loaded).comment("x").build());
        assertFalse(stale.isSuccess());
        assertEquals(SourceStatus.ACCEPTED, restarted.sources().get("src1").getStatus());
    }

    @Test
    public void unknownSourceIsNull() {
        ResearchProjectStore store = new ResearchProjectStore(tmp());
        assertNull(store.sources().get("nope"));
    }

    @Test
    public void corruptSourceFileIsIsolated() throws Exception {
        File root = folder.newFolder("project");
        ResearchProjectStore store = new ResearchProjectStore(root);
        store.sources().put(ResearchSourceRecord.builder("good").title("ok").revision(1L).build());
        // A corrupt file with no sourceId must not break find() and yields null from get().
        StoreIo.atomicWrite(new File(root, "sources/broken.properties"), "garbage-without-id\n");
        assertEquals(1, store.sources().find(SourceQuery.all()).size()); // only the good one
    }

    @Test
    public void sessionStateRoundTripAndCorruptionIsolated() throws Exception {
        File root = folder.newFolder("project");
        ResearchProjectStore store = new ResearchProjectStore(root);
        store.sessionState().save(new ResearchStateMemento(ResearchStateIds.RESEARCH,
                ResearchStateIds.PAUSED, ResearchStateIds.RUNNING, 17L, null));
        assertTrue(new File(root, "state/research-session.json").isFile());

        ResearchStateMemento restored = new ResearchProjectStore(root).sessionState().load();
        assertEquals(ResearchStateIds.RESEARCH, restored.getPhaseId());
        assertEquals(ResearchStateIds.PAUSED, restored.getStateId());
        assertEquals(ResearchStateIds.RUNNING, restored.getContinuationStateId());
        assertEquals(17L, restored.getRevision());
        assertNull(restored.getPendingApprovalId());

        StoreIo.atomicWrite(new File(root, "state/research-session.json"), "{ not valid");
        assertNull(new ResearchProjectStore(root).sessionState().load()); // corrupt -> no restored state
    }

    @Test
    public void twoProjectsAreIsolated() {
        ResearchProjectStore a = new ResearchProjectStore(tmp());
        ResearchProjectStore b = new ResearchProjectStore(tmp());
        a.artifacts().replace("outline", 0L, "A outline");
        b.artifacts().replace("outline", 0L, "B outline");
        assertEquals("A outline", a.artifacts().read("outline").getMarkdown());
        assertEquals("B outline", b.artifacts().read("outline").getMarkdown());
    }

    private File tmp() {
        try {
            return folder.newFolder();
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
