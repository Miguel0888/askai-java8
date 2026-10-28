package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The RATIFIED positional move (slice B): the anchor targets the POST-IMAGE (source out
 * first, then placed before the anchor in what remains — the off-by-one killer), the three
 * NO_CHANGE pins, INVALID_TARGET for self-parenting, and GPT's [A, B, C, D] series verbatim.
 */
public class ConceptMoveLeafByIdTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ConceptBranchService service;
    private String epoch;

    private String id(String... path) {
        return service.nodeIdAtPath(Arrays.asList(path));
    }

    private List<String> rootOrder() {
        List<String> names = new java.util.ArrayList<String>();
        for (List<String> path : ConceptTopicScanner.collectCardPaths(
                service.snapshot().getDocumentJson())) {
            if (path.size() == 1) {
                names.add(path.get(0));
            }
        }
        return names;
    }

    private void seedAbcd() throws Exception {
        service = new ConceptBranchService(new FileConceptStore(temp.newFolder("concept")));
        service.addCards(Collections.<String>emptyList(),
                Arrays.asList("A", "B", "C", "D"));
        epoch = service.currentEpoch();
    }

    @Test
    public void gptsSameParentSeriesVerbatim() throws Exception {
        seedAbcd();
        String idB = id("B");

        // move B before D → [A, C, B, D]
        ConceptBranchService.MoveLeafResult first =
                service.moveLeafById(epoch, idB, null, id("D"));
        assertTrue(first.isApplied());
        assertFalse(first.isNoChange());
        assertEquals(Arrays.asList("A", "C", "B", "D"), rootOrder());
        assertEquals("one revision per reorder", 2L, first.getNewRevision());

        // move C before A → [C, A, B, D]
        assertTrue(service.moveLeafById(epoch, id("C"), null, id("A")).isApplied());
        assertEquals(Arrays.asList("C", "A", "B", "D"), rootOrder());

        // move B before C, wenn B bereits direkt vor C steht → hier: B vor D testet die
        // Regel: B steht direkt vor D → NO_CHANGE.
        ConceptBranchService.MoveLeafResult alreadyBefore =
                service.moveLeafById(epoch, id("B"), null, id("D"));
        assertTrue(alreadyBefore.isApplied());
        assertTrue("already directly before the anchor", alreadyBefore.isNoChange());
        assertEquals(3L, service.snapshot().getWorkingRevision());

        // move D to end → NO_CHANGE (already last, null anchor).
        ConceptBranchService.MoveLeafResult toEnd =
                service.moveLeafById(epoch, id("D"), null, null);
        assertTrue(toEnd.isApplied());
        assertTrue(toEnd.isNoChange());

        // move B before B → NO_CHANGE.
        ConceptBranchService.MoveLeafResult beforeSelf =
                service.moveLeafById(epoch, id("B"), null, id("B"));
        assertTrue(beforeSelf.isApplied());
        assertTrue(beforeSelf.isNoChange());

        assertEquals("the UUID never changed through the series", idB, id("B"));
        assertEquals(Arrays.asList("C", "A", "B", "D"), rootOrder());
    }

    @Test
    public void selfParentingIsInvalidTargetWithZeroMutation() throws Exception {
        seedAbcd();
        long revision = service.snapshot().getWorkingRevision();
        ConceptBranchService.MoveLeafResult invalid =
                service.moveLeafById(epoch, id("A"), id("A"), null);
        assertFalse(invalid.isApplied());
        assertTrue(invalid.getDiagnostic().describeForModel().contains("INVALID_TARGET"));
        assertEquals("null mutation, null revision", revision,
                service.snapshot().getWorkingRevision());
    }

    @Test
    public void crossParentMovesHitTheExactFlatPositionAndRefusalsStayClean() throws Exception {
        seedAbcd();
        service.addCards(Collections.singletonList("A"),
                Arrays.asList("A1", "A2"));
        String idB = id("B");

        // Into A, before A2 — exact position, same UUID, one revision.
        long before = service.snapshot().getWorkingRevision();
        ConceptBranchService.MoveLeafResult moved =
                service.moveLeafById(epoch, idB, id("A"), id("A", "A2"));
        assertTrue(moved.isApplied());
        assertEquals(before + 1, moved.getNewRevision());
        assertEquals(Arrays.asList("A", "B"), service.pathOfNodeId(idB));
        assertEquals(Arrays.asList(
                        Arrays.asList("A"), Arrays.asList("A", "A1"),
                        Arrays.asList("A", "B"), Arrays.asList("A", "A2"),
                        Arrays.asList("C"), Arrays.asList("D")),
                ConceptTopicScanner.collectCardPaths(service.snapshot().getDocumentJson()));

        // Anchor not under the target anymore → whole refusal, nothing moved.
        long revision = service.snapshot().getWorkingRevision();
        ConceptBranchService.MoveLeafResult foreignAnchor =
                service.moveLeafById(epoch, id("C"), null, id("A", "A1"));
        assertFalse(foreignAnchor.isApplied());
        assertTrue(foreignAnchor.getDiagnostic().describeForModel()
                .contains("ANCHOR_NOT_IN_TARGET"));

        // Branch source and stale epoch refuse without mutation.
        ConceptBranchService.MoveLeafResult branch =
                service.moveLeafById(epoch, id("A"), null, null);
        assertFalse(branch.isApplied());
        assertTrue(branch.getDiagnostic().describeForModel().contains("SOURCE_NOT_LEAF"));
        ConceptBranchService.MoveLeafResult staleEpoch =
                service.moveLeafById("e-not-this-epoch", id("C"), null, null);
        assertFalse(staleEpoch.isApplied());
        assertEquals(revision, service.snapshot().getWorkingRevision());
    }
}
