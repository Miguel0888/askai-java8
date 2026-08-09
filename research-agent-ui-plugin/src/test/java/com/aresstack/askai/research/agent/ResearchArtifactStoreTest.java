package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The in-memory artifact store: EMPTY at creation (no invented sample artifacts), monotonic revisions,
 * optimistic locking on writes. */
public class ResearchArtifactStoreTest {

    @Test
    public void aFreshStoreContainsNoInventedArtifacts() {
        // Regression (user-reported clickdummy behavior): a fabricated outline at revision 1 caused an
        // approval for content nobody created. Every artifact starts empty at revision 0.
        ResearchArtifactStore store = new ResearchArtifactStore();
        for (String id : new String[]{"outline", "document"}) {
            assertEquals("", store.read(id).getMarkdown());
            assertEquals(0L, store.read(id).getRevision());
        }
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
        ArtifactWriteResult result = store.replace("outline", 0L, "# New outline\n");
        assertTrue(result.isSuccess());
        assertEquals(1L, result.getRevision());
        assertEquals("# New outline\n", store.read("outline").getMarkdown());
        assertEquals(1L, store.read("outline").getRevision());
    }

    @Test
    public void replaceWithStaleRevisionIsRejectedAsConflict() {
        ResearchArtifactStore store = new ResearchArtifactStore();
        store.replace("outline", 0L, "first edit"); // now at rev 1
        ArtifactWriteResult stale = store.replace("outline", 0L, "second edit based on old copy");
        assertFalse(stale.isSuccess());
        assertEquals(1L, stale.getRevision());
        assertEquals("first edit", stale.getCurrentMarkdown());
        // The stale write must NOT have applied.
        assertEquals("first edit", store.read("outline").getMarkdown());
    }
}
