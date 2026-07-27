package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The in-memory artifact store: seeded markdown, monotonic revisions, and optimistic-locking on writes. */
public class ResearchArtifactStoreTest {

    @Test
    public void seededMarkdownArtifactsAreReadableAtRevisionOne() {
        ResearchArtifactStore store = new ResearchArtifactStore();
        ArtifactContent outline = store.read("outline");
        assertTrue(outline.getMarkdown().contains("Research Outline"));
        assertEquals(1L, outline.getRevision());
    }

    @Test
    public void unknownArtifactReadsEmptyAtRevisionZero() {
        ResearchArtifactStore store = new ResearchArtifactStore();
        ArtifactContent missing = store.read("does-not-exist");
        assertEquals("", missing.getMarkdown());
        assertEquals(0L, missing.getRevision());
    }

    @Test
    public void replaceWithMatchingRevisionSucceedsAndBumpsRevision() {
        ResearchArtifactStore store = new ResearchArtifactStore();
        ArtifactWriteResult result = store.replace("outline", 1L, "# New outline\n");
        assertTrue(result.isSuccess());
        assertEquals(2L, result.getRevision());
        assertEquals("# New outline\n", store.read("outline").getMarkdown());
        assertEquals(2L, store.read("outline").getRevision());
    }

    @Test
    public void replaceWithStaleRevisionIsRejectedAsConflict() {
        ResearchArtifactStore store = new ResearchArtifactStore();
        store.replace("outline", 1L, "first edit"); // now at rev 2
        ArtifactWriteResult stale = store.replace("outline", 1L, "second edit based on old copy");
        assertFalse(stale.isSuccess());
        assertEquals(2L, stale.getRevision());
        assertEquals("first edit", stale.getCurrentMarkdown());
        // The stale write must NOT have applied.
        assertEquals("first edit", store.read("outline").getMarkdown());
    }
}
