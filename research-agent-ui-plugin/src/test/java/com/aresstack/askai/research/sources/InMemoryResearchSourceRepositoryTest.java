package com.aresstack.askai.research.sources;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The structured source repository: filtering, edits, chapter links, optimistic locking, error isolation. */
public class InMemoryResearchSourceRepositoryTest {

    private static final java.util.Set<String> KNOWN_SECTIONS =
            new java.util.HashSet<String>(Arrays.asList("s1", "s2", "s2a", "s3", "s4"));

    @Test
    public void listAndFilter() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        assertEquals(3, repo.find(SourceQuery.all()).size());
        assertEquals(1, repo.find(new SourceQuery("solon", null)).size());
        assertEquals(1, repo.find(new SourceQuery("", SourceStatus.ACCEPTED)).size());
        assertTrue(repo.find(new SourceQuery("nomatch", null)).isEmpty());
    }

    @Test
    public void loadRecord() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        ResearchSourceRecord src = repo.get("src1");
        assertEquals("PF4J plugin framework", src.getTitle());
        assertEquals(SourceStatus.ACCEPTED, src.getStatus());
    }

    @Test
    public void unknownSourceId() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        assertNull(repo.get("nope"));
        assertEquals(SourceUpdateResult.Status.NOT_FOUND,
                repo.update("nope", 1L, SourceUpdate.from(repo.get("src1")).build()).getStatus());
    }

    @Test
    public void changeCommentStatusRelevanceReliability() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        ResearchSourceRecord src = repo.get("src2");
        SourceUpdate update = SourceUpdate.from(src)
                .comment("Updated comment").status(SourceStatus.ACCEPTED)
                .relevance(SourceRelevance.HIGH).reliability(SourceReliability.HIGH).build();
        SourceUpdateResult result = repo.update("src2", src.getRevision(), update);
        assertTrue(result.isSuccess());
        ResearchSourceRecord updated = result.getRecord();
        assertEquals("Updated comment", updated.getComment());
        assertEquals(SourceStatus.ACCEPTED, updated.getStatus());
        assertEquals(SourceRelevance.HIGH, updated.getRelevance());
        assertEquals(SourceReliability.HIGH, updated.getReliability());
        assertEquals(src.getRevision() + 1, updated.getRevision());
    }

    @Test
    public void linkAndUnlinkMultipleSections() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        ResearchSourceRecord src = repo.get("src1");
        SourceUpdate linked = SourceUpdate.from(src).addSection("s1").addSection("s4").build();
        ResearchSourceRecord afterLink = repo.update("src1", src.getRevision(), linked).getRecord();
        assertTrue(afterLink.getLinkedSectionIds().containsAll(Arrays.asList("s1", "s3", "s4")));

        SourceUpdate unlinked = SourceUpdate.from(afterLink).removeSection("s3").build();
        ResearchSourceRecord afterUnlink = repo.update("src1", afterLink.getRevision(), unlinked).getRecord();
        assertFalse(afterUnlink.getLinkedSectionIds().contains("s3"));
    }

    @Test
    public void orphanSectionLinkIsPreservedAndDetectable() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        ResearchSourceRecord src = repo.get("src3");
        List<String> links = src.getLinkedSectionIds();
        assertTrue(links.contains("s99-removed"));
        // An orphan is a linked id not in the known outline sections — detectable, never auto-removed.
        boolean hasOrphan = false;
        for (String id : links) {
            if (!KNOWN_SECTIONS.contains(id)) {
                hasOrphan = true;
            }
        }
        assertTrue(hasOrphan);
    }

    @Test
    public void optimisticLockingConflictDoesNotOverwrite() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        ResearchSourceRecord src = repo.get("src1");
        repo.update("src1", src.getRevision(), SourceUpdate.from(src).comment("first").build()); // rev -> 2
        SourceUpdateResult stale =
                repo.update("src1", src.getRevision(), SourceUpdate.from(src).comment("second").build());
        assertEquals(SourceUpdateResult.Status.CONFLICT, stale.getStatus());
        assertEquals("first", repo.get("src1").getComment()); // the stale write did not apply
        assertEquals(2L, stale.getRecord().getRevision());     // conflict carries current record
    }

    @Test
    public void agentAndUiSeeTheSameDataThroughOneRepository() {
        ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        ResearchSourceRecord src = repo.get("src1");
        repo.update("src1", src.getRevision(), SourceUpdate.from(src).status(SourceStatus.EXCLUDED).build());
        // A second reader (the "agent") observes the change immediately — one shared store, no divergent copy.
        assertEquals(SourceStatus.EXCLUDED, repo.get("src1").getStatus());
    }
}
