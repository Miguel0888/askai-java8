package com.aresstack.askai.research.store;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The pure research-brief aggregate: change detection and approval-into-immutable-revisions. */
public class ResearchBriefArtifactTest {

    @Test
    public void aFirstBriefCreatesAWorkingCopy() {
        ResearchBriefArtifact.Update update = ResearchBriefArtifact.empty().withWorkingCopyUpdatedTo("A", 1L);
        assertTrue("the first brief is a change", update.isChanged());
        assertTrue(update.getArtifact().hasWorkingCopy());
        assertEquals("A", update.getArtifact().effectiveContent());
        assertTrue("nothing is approved yet", update.getArtifact().getApprovedRevisions().isEmpty());
    }

    @Test
    public void anIdenticalBriefIsNotAChange() {
        ResearchBriefArtifact withA = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact();
        // Same content (even with trivial trailing whitespace) is a no-op.
        ResearchBriefArtifact.Update again = withA.withWorkingCopyUpdatedTo("A\n", 2L);
        assertFalse(again.isChanged());
    }

    @Test
    public void aChangedBriefUpdatesTheWorkingCopy() {
        ResearchBriefArtifact withA = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact();
        ResearchBriefArtifact.Update toB = withA.withWorkingCopyUpdatedTo("B", 2L);
        assertTrue(toB.isChanged());
        assertEquals("B", toB.getArtifact().effectiveContent());
    }

    @Test
    public void approvalCreatesAnImmutableRevisionAndConsumesTheWorkingCopy() {
        ResearchBriefArtifact withA = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact();
        ResearchBriefArtifact.Approval approval = withA.approve(2L);

        assertEquals(BriefApprovalStatus.APPROVED, approval.getStatus());
        ResearchBriefArtifact approved = approval.getArtifact();
        assertFalse("the working copy is consumed by approval", approved.hasWorkingCopy());
        assertEquals(1, approved.getApprovedRevisions().size());
        assertEquals(1, approved.latestApprovedRevisionNumber());
        assertEquals("A", approved.latestApprovedRevision().getContent());
        assertEquals("effective content equals the approved content", "A", approved.effectiveContent());
    }

    @Test
    public void aChangeAfterApprovalKeepsTheOldRevisionAndBasesTheWorkingCopyOnIt() {
        ResearchBriefArtifact approved = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact()
                .approve(2L).getArtifact();
        ResearchBriefArtifact.Update toB = approved.withWorkingCopyUpdatedTo("B", 3L);

        assertTrue(toB.isChanged());
        ResearchBriefArtifact working = toB.getArtifact();
        assertEquals("the working copy is based on revision 1", 1,
                working.getWorkingCopy().getBaseApprovedRevision());
        assertEquals("revision 1 is unchanged", "A",
                working.getApprovedRevisions().get(0).getContent());
    }

    @Test
    public void aSecondApprovalAppendsRevisionTwoAndRevisionOneStaysReadable() {
        ResearchBriefArtifact.Approval second = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact()
                .approve(2L).getArtifact()
                .withWorkingCopyUpdatedTo("B", 3L).getArtifact()
                .approve(4L);

        assertEquals(BriefApprovalStatus.APPROVED, second.getStatus());
        assertEquals(2, second.getRevision().getRevisionNumber());
        assertEquals("revision 2 supersedes revision 1", 1, second.getRevision().getPreviousRevision());
        assertEquals(2, second.getArtifact().getApprovedRevisions().size());
        assertEquals("revision 1 remains readable", "A",
                second.getArtifact().getApprovedRevisions().get(0).getContent());
        assertEquals("B", second.getArtifact().latestApprovedRevision().getContent());
    }

    @Test
    public void approvingWithoutAChangeCreatesNoDuplicateRevision() {
        ResearchBriefArtifact approved = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact()
                .approve(2L).getArtifact();
        // User returns, changes nothing, clicks approve again.
        ResearchBriefArtifact.Approval again = approved.approve(3L);

        assertEquals(BriefApprovalStatus.ALREADY_CURRENT, again.getStatus());
        assertEquals("no duplicate revision", 1, again.getArtifact().getApprovedRevisions().size());
    }

    @Test
    public void latestApprovedIsNullBeforeAnyApproval() {
        ResearchBriefArtifact working = ResearchBriefArtifact.empty()
                .withWorkingCopyUpdatedTo("A", 1L).getArtifact();
        assertNull(working.latestApprovedRevision());
        assertEquals(0, working.latestApprovedRevisionNumber());
    }
}
