package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The atomic leaf move (move_leaf slice): one leaf, one existing target, one revision, the
 * UUID untouched — and every refusal (branch source, missing target, ambiguity, collision)
 * leaves document and sidecar byte-identical.
 */
public class ConceptMoveLeafTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** The live-gate tree: Betriebssysteme / Linux / Scheduling + FreeRTOS. */
    private ConceptBranchService seeded() throws Exception {
        ConceptBranchService service =
                new ConceptBranchService(new FileConceptStore(temp.newFolder("concept")));
        service.addCards(Collections.<String>emptyList(),
                Collections.singletonList("Betriebssysteme"));
        service.addCards(Collections.singletonList("Betriebssysteme"),
                Arrays.asList("Linux", "FreeRTOS"));
        service.addCards(Arrays.asList("Betriebssysteme", "Linux"),
                Collections.singletonList("Scheduling"));
        return service;
    }

    @Test
    public void aLeafMovesBetweenParentsInOneRevisionWithItsUuid() throws Exception {
        ConceptBranchService service = seeded();
        String uuid = service.nodeIdAtPath(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling"));
        long revision = service.snapshot().getWorkingRevision();

        ConceptBranchService.MoveLeafResult moved = service.moveLeaf(
                Collections.singletonList("Scheduling"),
                Collections.singletonList("FreeRTOS"));
        assertTrue(moved.isApplied());
        assertFalse(moved.isNoChange());
        assertEquals("exactly ONE new revision for tree AND sidecar",
                revision + 1, moved.getNewRevision());
        assertEquals("Scheduling", moved.getLabel());
        assertEquals(Arrays.asList("Betriebssysteme", "Linux", "Scheduling"),
                moved.getFromPath());
        assertEquals(Arrays.asList("Betriebssysteme", "FreeRTOS", "Scheduling"),
                moved.getToPath());
        assertEquals("the UUID survives the move", uuid, moved.getNodeId());
        assertEquals(uuid, service.nodeIdAtPath(
                Arrays.asList("Betriebssysteme", "FreeRTOS", "Scheduling")));
        assertEquals("suppression/conflict references keep resolving via the SAME id",
                Arrays.asList("Betriebssysteme", "FreeRTOS", "Scheduling"),
                service.pathOfNodeId(uuid));
    }

    @Test
    public void movesToRootAndFromRootKeepTheUuid() throws Exception {
        ConceptBranchService service = seeded();
        String uuid = service.nodeIdAtPath(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling"));

        ConceptBranchService.MoveLeafResult toRoot = service.moveLeaf(
                Collections.singletonList("Scheduling"), Collections.<String>emptyList());
        assertTrue(toRoot.isApplied());
        assertEquals(Collections.singletonList("Scheduling"), toRoot.getToPath());
        assertEquals(uuid, service.nodeIdAtPath(Collections.singletonList("Scheduling")));

        ConceptBranchService.MoveLeafResult back = service.moveLeaf(
                Collections.singletonList("Scheduling"),
                Collections.singletonList("Linux"));
        assertTrue(back.isApplied());
        assertEquals(uuid, service.nodeIdAtPath(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling")));
    }

    @Test
    public void branchSourceMissingTargetAmbiguityAndCollisionAllRefuseWithoutMutation()
            throws Exception {
        ConceptBranchService service = seeded();
        long revision = service.snapshot().getWorkingRevision();

        ConceptBranchService.MoveLeafResult branch = service.moveLeaf(
                Collections.singletonList("Linux"),
                Collections.singletonList("FreeRTOS"));
        assertFalse(branch.isApplied());
        assertTrue(branch.getDiagnostic().describeForModel().contains("SOURCE_NOT_LEAF"));

        ConceptBranchService.MoveLeafResult missing = service.moveLeaf(
                Collections.singletonList("Scheduling"),
                Collections.singletonList("Gibtsnicht"));
        assertFalse(missing.isApplied());
        assertTrue(missing.getDiagnostic().describeForModel()
                .contains("TARGET_PARENT_NOT_FOUND"));

        // A second "Scheduling" elsewhere: the short SOURCE name becomes ambiguous.
        service.addCards(Collections.singletonList("FreeRTOS"),
                Collections.singletonList("Scheduling"));
        ConceptBranchService.MoveLeafResult ambiguousSource = service.moveLeaf(
                Collections.singletonList("Scheduling"),
                Collections.<String>emptyList());
        assertFalse(ambiguousSource.isApplied());
        assertTrue(ambiguousSource.getDiagnostic().describeForModel()
                .contains("AMBIGUOUS_SOURCE"));

        // The full-path move onto a parent already holding the same name: collision, no merge.
        ConceptBranchService.MoveLeafResult collision = service.moveLeaf(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling"),
                Collections.singletonList("FreeRTOS"));
        assertFalse(collision.isApplied());
        assertTrue(collision.getDiagnostic().describeForModel()
                .contains("TARGET_NAME_COLLISION"));

        // Ambiguous PARENT short name: a second "Linux" branch elsewhere.
        service.addCards(Collections.<String>emptyList(),
                Collections.singletonList("Vergleich"));
        service.addCards(Collections.singletonList("Vergleich"),
                Collections.singletonList("Linux"));
        ConceptBranchService.MoveLeafResult ambiguousParent = service.moveLeaf(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling"),
                Collections.singletonList("Linux"));
        assertFalse(ambiguousParent.isApplied());
        assertTrue(ambiguousParent.getDiagnostic().describeForModel()
                .contains("AMBIGUOUS_PARENT"));

        // The ambiguity setups added cards deliberately (3 add revisions); the FOUR refusals
        // themselves committed nothing.
        assertEquals("refusals never create a revision", revision + 3,
                service.snapshot().getWorkingRevision());
        assertTrue("Scheduling still sits under Linux", service.snapshot().getDocumentJson()
                .contains("\"Linux\":[{\"Scheduling\""));
    }

    @Test
    public void movingOntoTheCurrentParentIsAnHonestNoChange() throws Exception {
        ConceptBranchService service = seeded();
        long revision = service.snapshot().getWorkingRevision();
        String uuid = service.nodeIdAtPath(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling"));
        ConceptBranchService.MoveLeafResult result = service.moveLeaf(
                Collections.singletonList("Scheduling"),
                Collections.singletonList("Linux"));
        assertTrue(result.isApplied());
        assertTrue(result.isNoChange());
        assertEquals("the revision stays", revision, result.getNewRevision());
        assertEquals(uuid, result.getNodeId());
        assertEquals(revision, service.snapshot().getWorkingRevision());
    }

    /** Test 15: restore around a move brings tree AND the identical UUID back per state. */
    @Test
    public void restoreBeforeAndAfterAMoveKeepsTreeAndUuidConsistent() throws Exception {
        ConceptBranchService service = seeded();
        long preMoveRevision = service.snapshot().getWorkingRevision();
        String uuid = service.nodeIdAtPath(
                Arrays.asList("Betriebssysteme", "Linux", "Scheduling"));
        ConceptBranchService.MoveLeafResult moved = service.moveLeaf(
                Collections.singletonList("Scheduling"),
                Collections.singletonList("FreeRTOS"));
        long postMoveRevision = moved.getNewRevision();

        assertTrue(service.restoreRevision(preMoveRevision).isApplied());
        assertEquals("restored PRE-move: same UUID at the old path", uuid,
                service.nodeIdAtPath(Arrays.asList("Betriebssysteme", "Linux", "Scheduling")));

        assertTrue(service.restoreRevision(postMoveRevision).isApplied());
        assertEquals("restored POST-move: same UUID at the new path", uuid,
                service.nodeIdAtPath(
                        Arrays.asList("Betriebssysteme", "FreeRTOS", "Scheduling")));
    }
}
