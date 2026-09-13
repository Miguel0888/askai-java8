package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The atomic list add (add_cards slice): all-or-nothing under ONE parent, one revision,
 * idempotent repeats, in-list dedupe, typed multi-word names, and one fresh UUID per new card
 * in the shared identity candidate. The live gate that motivated this: four one-by-one add
 * rounds cost a user-named area ("Debugging") its place in the tool budget.
 */
public class ConceptAddCardsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ConceptBranchService fresh() throws Exception {
        return new ConceptBranchService(new FileConceptStore(temp.newFolder("concept")));
    }

    @Test
    public void sixRootAreasLandInOneCallAndOneRevision() throws Exception {
        ConceptBranchService service = fresh();
        ConceptBranchService.AddCardsResult result = service.addCards(
                Collections.<String>emptyList(),
                Arrays.asList("Grundlagen", "Entwicklungsumgebung", "Architektur", "Tasks",
                        "Synchronisation", "Debugging"));
        assertTrue(result.isApplied());
        assertEquals("ONE revision for the whole list", 1L, result.getNewRevision());
        assertEquals(6, result.getAdded().size());
        assertTrue(result.getAlreadyPresent().isEmpty());
        String document = service.snapshot().getDocumentJson();
        for (String name : result.getAdded()) {
            assertTrue(document.contains("\"" + name + "\""));
        }
        // Flat under root, and each new card carries exactly one fresh UUID (test 9).
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (String name : result.getAdded()) {
            String id = service.nodeIdAtPath(Collections.singletonList(name));
            assertNotNull(name + " has an identity", id);
            assertTrue("distinct UUID per card", ids.add(id));
        }
    }

    @Test
    public void threeCardsUnderAnExistingParentAndSingleElementLists() throws Exception {
        ConceptBranchService service = fresh();
        assertTrue(service.addCards(Collections.<String>emptyList(),
                Collections.singletonList("FreeRTOS")).isApplied()); // test 3: 1-element list
        ConceptBranchService.AddCardsResult result = service.addCards(
                Collections.singletonList("FreeRTOS"),
                Arrays.asList("Tasks", "Queues", "Semaphoren"));
        assertTrue(result.isApplied());
        assertEquals(3, result.getAdded().size());
        assertEquals(2L, result.getNewRevision());
        assertNotNull(service.nodeIdAtPath(Arrays.asList("FreeRTOS", "Queues")));
    }

    @Test
    public void repeatsAndDuplicatesAreIdempotentWithoutARevisionBump() throws Exception {
        ConceptBranchService service = fresh();
        // Test 5: in-list duplicates collapse to one card.
        ConceptBranchService.AddCardsResult first = service.addCards(
                Collections.<String>emptyList(),
                Arrays.asList("Grundlagen", "Grundlagen", "Debugging"));
        assertTrue(first.isApplied());
        assertEquals(2, first.getAdded().size());
        long revision = first.getNewRevision();
        // Test 4: repeating the same list is a no-op — ALREADY_PRESENT, same revision.
        ConceptBranchService.AddCardsResult repeat = service.addCards(
                Collections.<String>emptyList(),
                Arrays.asList("Grundlagen", "Debugging"));
        assertTrue(repeat.isApplied());
        assertTrue(repeat.getAdded().isEmpty());
        assertEquals(Arrays.asList("Grundlagen", "Debugging"), repeat.getAlreadyPresent());
        assertEquals("no revision bump on an idempotent repeat", revision,
                repeat.getNewRevision());
        assertEquals(revision, service.snapshot().getWorkingRevision());
        // Test 6: an existing sibling is never duplicated when the list adds new ones.
        ConceptBranchService.AddCardsResult mixed = service.addCards(
                Collections.<String>emptyList(),
                Arrays.asList("Grundlagen", "Architektur"));
        assertTrue(mixed.isApplied());
        assertEquals(Collections.singletonList("Architektur"), mixed.getAdded());
        assertEquals(Collections.singletonList("Grundlagen"), mixed.getAlreadyPresent());
    }

    @Test
    public void multiWordNamesStayOneCardAndAMissingParentMutatesNothing() throws Exception {
        ConceptBranchService service = fresh();
        // Test 7: "Computer Science" is ONE name — the list is typed, never space-split.
        assertTrue(service.addCards(Collections.<String>emptyList(),
                Arrays.asList("Computer Science", "Embedded Development Environment"))
                .isApplied());
        assertNotNull(service.nodeIdAtPath(
                Collections.singletonList("Computer Science")));
        long revision = service.snapshot().getWorkingRevision();
        // Test 8: an unresolvable parent PREFIX rejects the WHOLE list — no partial mutation
        // (a missing single-segment parent is the legal created-parent case instead).
        ConceptBranchService.AddCardsResult refused = service.addCards(
                Arrays.asList("Gibtsnicht", "Tiefer"),
                Arrays.asList("A", "B"));
        assertFalse(refused.isApplied());
        assertEquals("nothing moved", revision, service.snapshot().getWorkingRevision());
        assertFalse(service.snapshot().getDocumentJson().contains("\"A\""));
    }

    /** Gate correction: the ONE missing terminal parent is created WITH its cards, atomically. */
    @Test
    public void aMissingParentIsCreatedTogetherWithItsCardsInOneRevision() throws Exception {
        ConceptBranchService service = fresh();
        ConceptBranchService.AddCardsResult result = service.addCards(
                Collections.singletonList("FreeRTOS"),
                Arrays.asList("Grundlagen", "Tasks"));
        assertTrue(result.isApplied());
        assertEquals("FreeRTOS", result.getCreatedParent());
        assertEquals("parent AND children in ONE revision", 1L, result.getNewRevision());
        assertEquals(2, result.getAdded().size());
        assertNotNull(service.nodeIdAtPath(Collections.singletonList("FreeRTOS")));
        assertNotNull(service.nodeIdAtPath(Arrays.asList("FreeRTOS", "Tasks")));

        // Multi-segment: only the TERMINAL parent may appear; a missing prefix refuses whole.
        ConceptBranchService.AddCardsResult terminal = service.addCards(
                Arrays.asList("FreeRTOS", "Praxis"), Collections.singletonList("Beispiele"));
        assertTrue(terminal.isApplied());
        assertEquals("Praxis", terminal.getCreatedParent());
        ConceptBranchService.AddCardsResult deep = service.addCards(
                Arrays.asList("Gibtsnicht", "Tiefer"), Collections.singletonList("X"));
        assertFalse("no silent deep chains", deep.isApplied());
    }

    /** Gate correction: a single-segment parent resolves as a globally UNIQUE card name. */
    @Test
    public void aShortParentNameResolvesGloballyAndAmbiguityRejectsWholeCall() throws Exception {
        ConceptBranchService service = fresh();
        service.addCards(Collections.singletonList("FreeRTOS"),
                Collections.singletonList("Architektur"));
        ConceptBranchService.AddCardsResult resolved = service.addCards(
                Collections.singletonList("Architektur"),
                Arrays.asList("Scheduler", "Speicherverwaltung"));
        assertTrue("the short name found [FreeRTOS, Architektur]", resolved.isApplied());
        assertEquals(null, resolved.getCreatedParent());
        assertNotNull(service.nodeIdAtPath(
                Arrays.asList("FreeRTOS", "Architektur", "Scheduler")));

        // A second "Architektur" elsewhere makes the short name ambiguous → whole refusal.
        service.addCards(Collections.singletonList("Praxis"),
                Collections.singletonList("Architektur"));
        long revision = freshRevision(service);
        ConceptBranchService.AddCardsResult ambiguous = service.addCards(
                Collections.singletonList("Architektur"),
                Collections.singletonList("Interrupts"));
        assertFalse(ambiguous.isApplied());
        String diagnostic = ambiguous.getDiagnostic().describeForModel();
        assertTrue(diagnostic, diagnostic.contains("AMBIGUOUS_PARENT"));
        assertTrue("the candidates carry FULL paths",
                diagnostic.contains("[FreeRTOS, Architektur]")
                        && diagnostic.contains("[Praxis, Architektur]"));
        assertEquals("no mutation on ambiguity", revision, freshRevision(service));
    }

    private static long freshRevision(ConceptBranchService service) {
        return service.snapshot().getWorkingRevision();
    }

    /**
     * The tree editor's ID adapters (GPT's hardening ruling): gestures travel as
     * (epoch, nodeId); the path resolves INSIDE the atomic operation — a stale epoch or a
     * vanished id aborts honestly, and a rename between render and click can never make the
     * gesture hit a different card.
     */
    @Test
    public void idAdaptersResolveInsideTheAtomicOperationAndAbortOnStaleState() throws Exception {
        ConceptBranchService service = fresh();
        service.addCards(Collections.<String>emptyList(),
                Arrays.asList("Grundlagen", "Debugging"));
        String epoch = service.currentEpoch();
        String debuggingId = service.nodeIdAtPath(Collections.singletonList("Debugging"));

        // The stale-path bug class, killed: rename FIRST, then act via the OLD id — the
        // adapter resolves the CURRENT path and hits the renamed card, never a neighbour.
        assertTrue(service.renameNodeById(epoch, debuggingId, "Fehlersuche").isApplied());
        assertTrue(service.deleteTerminalBranchById(epoch, debuggingId).isApplied());
        assertFalse(service.snapshot().getDocumentJson().contains("Fehlersuche"));

        // Add under an identity + deep removal by identity.
        String grundlagenId = service.nodeIdAtPath(Collections.singletonList("Grundlagen"));
        ConceptBranchService.AddCardsResult added = service.addCardsUnderId(epoch,
                grundlagenId, Arrays.asList("Setup", "Praxis"));
        assertTrue(added.isApplied());
        assertEquals(2, added.getAdded().size());
        assertTrue(service.addCardsUnderId(epoch, null,
                Collections.singletonList("Anhang")).isApplied());
        assertTrue(service.removeNodeById(epoch, grundlagenId).isApplied());
        assertFalse(service.snapshot().getDocumentJson().contains("Setup"));

        // Stale epoch (raw save cut a fresh one) and vanished ids abort without mutation.
        ConceptBranchService.DocumentSnapshot head = service.snapshot();
        assertTrue(service.replaceDocument(head.getDocumentJson(),
                head.getWorkingRevision()).isApplied());
        long revision = service.snapshot().getWorkingRevision();
        ConceptBranchService.EditResult stale =
                service.renameNodeById(epoch, debuggingId, "X");
        assertFalse(stale.isApplied());
        assertTrue(stale.getDiagnostic().describeForModel().contains("epoch"));
        assertFalse(service.addCardsUnderId(epoch, null,
                Collections.singletonList("Y")).isApplied());
        ConceptBranchService.EditResult gone = service.deleteTerminalBranchById(
                service.currentEpoch(), debuggingId);
        assertFalse(gone.isApplied());
        assertTrue(gone.getDiagnostic().describeForModel().contains("no longer exists"));
        assertEquals("aborts never mutate", revision,
                service.snapshot().getWorkingRevision());
    }

    @Test
    public void renameAndIdentityKeepWorkingAfterAListAdd() throws Exception {
        ConceptBranchService service = fresh();
        service.addCards(Collections.<String>emptyList(),
                Arrays.asList("Grundlagen", "Debugging"));
        String debuggingId = service.nodeIdAtPath(Collections.singletonList("Debugging"));
        assertTrue(service.renameNode(Collections.singletonList("Debugging"),
                "Fehlersuche").isApplied());
        assertEquals("the list-added card keeps its UUID through a rename (test 10)",
                debuggingId, service.nodeIdAtPath(Collections.singletonList("Fehlersuche")));
        assertEquals(Collections.singletonList("Fehlersuche"),
                service.pathOfNodeId(debuggingId));
    }
}
