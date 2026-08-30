package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.jsontree.JsonTreeErrorCode;
import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The concept is built in BITES: read a branch by name, refine it, commit — never regenerate.
 * The service is the policy boundary: name-chain addressing, opaque handles, read-only
 * depth-limited reads, non-destructive refinements (moves allowed, silent loss rejected),
 * explicit removal, stale handles refused — and no failure ever changes the store.
 */
public class ConceptBranchServiceTest {

    private static final List<String> ROOT = Collections.emptyList();

    private FileConceptStore store;

    private ConceptBranchService fresh() throws Exception {
        File dir = Files.createTempDirectory("askai-concept-service").toFile();
        store = new FileConceptStore(new File(dir, "concept"));
        return new ConceptBranchService(store);
    }

    /** The canonical first turns: empty envelope → four cards → grouped refinement. */
    @Test
    public void theConceptGrowsInBitesFromTheEmptyEnvelope() throws Exception {
        ConceptBranchService service = fresh();
        // Turn 1: read the empty working surface, add the first cards.
        ConceptBranchService.ReadResult root = service.readBranch(ROOT, 0);
        assertTrue(root.isOk());
        assertEquals("{\"concept\":[]}", root.getBranchJson());
        assertTrue(root.isEditable());
        ConceptBranchService.EditResult cards = service.updateBranch(root.getHandleId(),
                "{\"concept\":[{\"Tasks\":[],\"Scheduling\":[],\"Echtzeit\":[],"
                        + "\"Mikrocontroller\":[]}]}");
        assertTrue(cards.isApplied());
        assertEquals(1L, cards.getNewRevision());
        // Turn 2: read one card and refine it with children.
        ConceptBranchService.ReadResult tasks =
                service.readBranch(Collections.singletonList("Tasks"), 0);
        assertTrue(tasks.isOk());
        assertEquals("{\"Tasks\":[]}", tasks.getBranchJson());
        assertEquals(Arrays.asList("Scheduling", "Echtzeit", "Mikrocontroller"),
                tasks.getSiblingNames());
        assertNull("top-level cards have no parent node", tasks.getParentName());
        ConceptBranchService.EditResult refined = service.updateBranch(tasks.getHandleId(),
                "{\"Tasks\":[{\"Zustaende\":[],\"Prioritaeten\":[]}]}");
        assertTrue(refined.isApplied());
        assertEquals(2L, refined.getNewRevision());
        // The deep branch is addressable by its name chain, with orientation metadata.
        ConceptBranchService.ReadResult deep =
                service.readBranch(Arrays.asList("Tasks", "Zustaende"), 0);
        assertTrue(deep.isOk());
        assertEquals("{\"Zustaende\":[]}", deep.getBranchJson());
        assertEquals("Tasks", deep.getParentName());
        assertEquals(Collections.singletonList("Prioritaeten"), deep.getSiblingNames());
    }

    @Test
    public void aMissingNodeNameIsARealDiagnosisNotACrash() throws Exception {
        ConceptBranchService service = fresh();
        ConceptBranchService.ReadResult result =
                service.readBranch(Collections.singletonList("Nirgendwo"), 0);
        assertFalse(result.isOk());
        assertEquals(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND, result.getDiagnostic().getCode());
    }

    // ------------------------------------------------------------------ non-destructive guard

    @Test
    public void silentlyDroppingAnExistingNodeIsRejectedWithTheLostNames() throws Exception {
        ConceptBranchService service = seeded();
        ConceptBranchService.ReadResult sync =
                service.readBranch(Collections.singletonList("Synchronisation"), 0);
        ConceptBranchService.EditResult result = service.updateBranch(sync.getHandleId(),
                "{\"Synchronisation\":[{\"Mutex\":[]}]}");
        assertFalse(result.isApplied());
        assertEquals(JsonTreeErrorCode.STRUCTURE_LOSS_DETECTED,
                result.getDiagnostic().getCode());
        assertTrue("the lost node is named",
                result.getDiagnostic().getMessage().contains("Semaphoren"));
        assertTrue("the repair hint points at the explicit removal path",
                result.getDiagnostic().getHint().contains("remove"));
    }

    @Test
    public void movingANodeWithinTheBranchIsARefinementNotALoss() throws Exception {
        ConceptBranchService service = seeded();
        ConceptBranchService.ReadResult root = service.readBranch(ROOT, 0);
        // GPT's regrouping example: Queues leaves Synchronisation, Kommunikation appears —
        // no node vanished, so the guard must stay silent.
        ConceptBranchService.EditResult result = service.updateBranch(root.getHandleId(),
                "{\"concept\":[{\"Synchronisation\":[{\"Mutex\":[],\"Semaphoren\":[]}],"
                        + "\"Kommunikation\":[{\"Queues\":[]}]}]}");
        assertTrue(result.isApplied());
    }

    @Test
    public void allowRemovalsMakesTheSameEditLegal() throws Exception {
        ConceptBranchService service = seeded();
        ConceptBranchService.ReadResult sync =
                service.readBranch(Collections.singletonList("Synchronisation"), 0);
        assertTrue(service.updateBranch(sync.getHandleId(),
                "{\"Synchronisation\":[{\"Mutex\":[]}]}", true).isApplied());
    }

    @Test
    public void removalIsItsOwnExplicitOperation() throws Exception {
        ConceptBranchService service = seeded();
        ConceptBranchService.ReadResult queues =
                service.readBranch(Arrays.asList("Synchronisation", "Queues"), 0);
        assertTrue(service.removeBranch(queues.getHandleId()).isApplied());
        assertTrue(service.readBranch(
                Collections.singletonList("Synchronisation"), 0).getBranchJson()
                .equals("{\"Synchronisation\":[{\"Mutex\":[],\"Semaphoren\":[]}]}"));
        // …but the working surface itself is never removable.
        ConceptBranchService.ReadResult root = service.readBranch(ROOT, 0);
        assertFalse(service.removeBranch(root.getHandleId()).isApplied());
    }

    // ------------------------------------------------------------------ staleness & depth

    @Test
    public void aStaleHandleIsRefusedAfterAnInterveningEdit() throws Exception {
        ConceptBranchService service = seeded();
        ConceptBranchService.ReadResult stale =
                service.readBranch(Collections.singletonList("Synchronisation"), 0);
        // Someone else (user in the JSON editor, another agent turn) commits meanwhile:
        ConceptBranchService.ReadResult other = service.readBranch(ROOT, 0);
        assertTrue(service.updateBranch(other.getHandleId(),
                "{\"concept\":[{\"Synchronisation\":[{\"Mutex\":[],\"Semaphoren\":[],"
                        + "\"Queues\":[]}],\"Neu\":[]}]}").isApplied());
        ConceptBranchService.EditResult result = service.updateBranch(stale.getHandleId(),
                "{\"Synchronisation\":[{\"Mutex\":[],\"Semaphoren\":[],\"Queues\":[],"
                        + "\"Event Groups\":[]}]}");
        assertFalse(result.isApplied());
        assertEquals(JsonTreeErrorCode.STALE_DOCUMENT_REVISION,
                result.getDiagnostic().getCode());
        assertTrue(result.getDiagnostic().getHint().contains("Read the branch again"));
    }

    @Test
    public void aDepthLimitedReadPrunesGrandchildrenAndIsReadOnly() throws Exception {
        ConceptBranchService service = fresh();
        ConceptBranchService.ReadResult root = service.readBranch(ROOT, 0);
        service.updateBranch(root.getHandleId(),
                "{\"concept\":[{\"FreeRTOS\":[{\"Grundlagen\":[{\"Echtzeit\":[],"
                        + "\"Scheduling\":[]}]}]}]}");
        ConceptBranchService.ReadResult shallow =
                service.readBranch(Collections.singletonList("FreeRTOS"), 1);
        assertEquals("depth 1: children visible, grandchildren pruned to []",
                "{\"FreeRTOS\":[{\"Grundlagen\":[]}]}", shallow.getBranchJson());
        assertFalse(shallow.isEditable());
        ConceptBranchService.EditResult write = service.updateBranch(shallow.getHandleId(),
                "{\"FreeRTOS\":[{\"Grundlagen\":[],\"Praxis\":[]}]}");
        assertFalse("writing a pruned branch back would wipe the pruned children",
                write.isApplied());
        assertTrue(write.getDiagnostic().getHint().contains("without a depth limit"));
        // The full-depth read stays editable and the grandchildren are intact.
        ConceptBranchService.ReadResult full =
                service.readBranch(Collections.singletonList("FreeRTOS"), 0);
        assertTrue(full.isEditable());
        assertTrue(full.getBranchJson().contains("Echtzeit"));
    }

    /** THE invariant, at service level: no failed edit ever changes store content or revision. */
    @Test
    public void everyRejectionLeavesTheStoreUntouched() throws Exception {
        ConceptBranchService service = seeded();
        String contentBefore = store.effectiveContent();
        long revisionBefore = store.workingRevision();
        ConceptBranchService.ReadResult sync =
                service.readBranch(Collections.singletonList("Synchronisation"), 0);
        // syntax error, loss, unknown handle — three rejection kinds in a row:
        assertFalse(service.updateBranch(sync.getHandleId(),
                "{\"Synchronisation\":[{\"Mutex\":[] \"Semaphoren\":[]}]}").isApplied());
        assertFalse(service.updateBranch(sync.getHandleId(),
                "{\"Synchronisation\":[{\"Mutex\":[]}]}").isApplied());
        assertFalse(service.updateBranch("b-doesnotexist",
                "{\"Synchronisation\":[]}").isApplied());
        assertEquals(contentBefore, store.effectiveContent());
        assertEquals(revisionBefore, store.workingRevision());
        // …and the handle is still good for a valid refinement afterwards.
        assertTrue(service.updateBranch(sync.getHandleId(),
                "{\"Synchronisation\":[{\"Mutex\":[],\"Semaphoren\":[],\"Queues\":[],"
                        + "\"Event Groups\":[]}]}").isApplied());
    }

    // ------------------------------------------------------------------ fixture

    /** A concept with one grouped card: Synchronisation(Mutex, Semaphoren, Queues). */
    private ConceptBranchService seeded() throws Exception {
        ConceptBranchService service = fresh();
        ConceptBranchService.ReadResult root = service.readBranch(ROOT, 0);
        assertTrue(service.updateBranch(root.getHandleId(),
                "{\"concept\":[{\"Synchronisation\":[{\"Mutex\":[],\"Semaphoren\":[],"
                        + "\"Queues\":[]}]}]}").isApplied());
        return service;
    }
}
