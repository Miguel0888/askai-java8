package com.aresstack.askai.research.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** File-backed research brief: working copy + immutable approved revisions survive a restart. */
public class FileResearchBriefStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private FileResearchBriefStore store(File dir) {
        return new FileResearchBriefStore(new File(dir, "brief"));
    }

    @Test
    public void aFirstBriefIsPersistedAsAWorkingCopyAndSurvivesRestart() throws Exception {
        File dir = folder.newFolder("p1");
        assertTrue(store(dir).updateWorkingCopy("# Brief\nWearables", 1L));
        assertTrue(new File(dir, "brief/working.md").isFile());

        // "Restart": a fresh store over the same directory restores the working copy.
        ResearchBriefArtifact reloaded = store(dir).load();
        assertTrue(reloaded.hasWorkingCopy());
        assertEquals("# Brief\nWearables", reloaded.effectiveContent());
        assertTrue("nothing is approved yet", reloaded.getApprovedRevisions().isEmpty());
    }

    @Test
    public void anIdenticalBriefWritesNothing() throws Exception {
        File dir = folder.newFolder("p2");
        assertTrue(store(dir).updateWorkingCopy("A", 1L));
        assertFalse("the same content is not a change", store(dir).updateWorkingCopy("A", 2L));
    }

    @Test
    public void approvalWritesAnImmutableRevisionAndClearsTheWorkingFile() throws Exception {
        File dir = folder.newFolder("p3");
        store(dir).updateWorkingCopy("A", 1L);
        ResearchBriefArtifact.Approval approval = store(dir).approveCurrent(2L);

        assertEquals(BriefApprovalStatus.APPROVED, approval.getStatus());
        assertTrue(new File(dir, "brief/revisions/0001.md").isFile());
        assertFalse("the transient working file is gone after approval",
                new File(dir, "brief/working.md").isFile());
        assertEquals("A", store(dir).latestApprovedContent());
    }

    @Test
    public void aSecondApprovalKeepsRevisionOneReadableAcrossRestart() throws Exception {
        File dir = folder.newFolder("p4");
        store(dir).updateWorkingCopy("A", 1L);
        store(dir).approveCurrent(2L);
        store(dir).updateWorkingCopy("B", 3L);
        store(dir).approveCurrent(4L);

        // Fresh store: both revisions restore, revision 1 unchanged, latest is revision 2.
        ResearchBriefArtifact reloaded = store(dir).load();
        assertEquals(2, reloaded.getApprovedRevisions().size());
        assertEquals("A", reloaded.getApprovedRevisions().get(0).getContent());
        assertEquals("B", reloaded.latestApprovedRevision().getContent());
        assertEquals("B", store(dir).latestApprovedContent());
    }

    @Test
    public void aChangeAfterApprovalIsAWorkingCopyOverTheApprovedRevision() throws Exception {
        File dir = folder.newFolder("p5");
        store(dir).updateWorkingCopy("A", 1L);
        store(dir).approveCurrent(2L);
        assertTrue("a real change after approval writes a working copy", store(dir).updateWorkingCopy("B", 3L));

        ResearchBriefArtifact reloaded = store(dir).load();
        assertTrue(reloaded.hasWorkingCopy());
        assertEquals("scoping works with the working copy", "B", reloaded.effectiveContent());
        assertEquals("other phases still read the approved revision", "A", store(dir).latestApprovedContent());
        assertEquals(1, reloaded.getWorkingCopy().getBaseApprovedRevision());
    }

    @Test
    public void approvingWithoutAChangeCreatesNoDuplicateRevision() throws Exception {
        File dir = folder.newFolder("p6");
        store(dir).updateWorkingCopy("A", 1L);
        store(dir).approveCurrent(2L);
        // Return, change nothing, approve again.
        ResearchBriefArtifact.Approval again = store(dir).approveCurrent(3L);

        assertEquals(BriefApprovalStatus.ALREADY_CURRENT, again.getStatus());
        assertEquals(1, store(dir).load().getApprovedRevisions().size());
        assertFalse(new File(dir, "brief/revisions/0002.md").isFile());
    }
}
