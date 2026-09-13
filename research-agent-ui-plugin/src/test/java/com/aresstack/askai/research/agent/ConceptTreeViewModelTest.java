package com.aresstack.askai.research.agent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The tree editor's row model (slice 1): depth-first document order, the structural classes the
 * hover actions route on (leaf → direct delete, terminal → guarded delete, deep → confirmed
 * host-authorized removal), and the suppression truth dimmed SUBTREE-DEEP — exactly the
 * effective-mindmap rule the projection uses.
 */
public class ConceptTreeViewModelTest {

    private static final String DOCUMENT = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":["
            + "{\"Buch\":[{\"Setup\":[{\"Arduino\":[],\"ESP-IDF\":[]}],\"Praxis\":[]}],"
            + "\"Anhang\":[]}]}";

    @Test
    public void rowsFollowDocumentOrderWithDepthAndStructuralClasses() {
        List<ConceptTreeView.Row> rows =
                ConceptTreeView.rowsOf(DOCUMENT, Collections.<String>emptyList());
        assertEquals(6, rows.size());
        assertEquals(Arrays.asList("Buch"), rows.get(0).path);
        assertEquals(0, rows.get(0).depth);
        assertFalse("Buch is DEEP — delete only via the confirmed branch action",
                rows.get(0).leaf || rows.get(0).terminal);
        assertEquals(4, rows.get(0).subtreeCards);
        assertEquals(Arrays.asList("Buch", "Setup"), rows.get(1).path);
        assertTrue("Setup is TERMINAL (children without own children)", rows.get(1).terminal);
        assertEquals(Arrays.asList("Buch", "Setup", "Arduino"), rows.get(2).path);
        assertTrue(rows.get(2).leaf);
        assertEquals(2, rows.get(2).depth);
        assertEquals(Arrays.asList("Buch", "Praxis"), rows.get(4).path);
        assertTrue(rows.get(4).leaf);
        assertEquals("Anhang", rows.get(5).name);
        assertEquals(0, rows.get(5).depth);
    }

    @Test
    public void suppressionDimsSubtreeDeepAndBrokenJsonYieldsNoRows() {
        List<ConceptTreeView.Row> rows = ConceptTreeView.rowsOf(DOCUMENT,
                Arrays.asList("setup", "irrelevant"));
        assertFalse("Buch itself is not suppressed", rows.get(0).suppressed);
        assertTrue("the exact match dims", rows.get(1).suppressed);
        assertTrue("…and its whole subtree with it (effective-mindmap rule)",
                rows.get(2).suppressed && rows.get(3).suppressed);
        assertFalse("siblings stay bright", rows.get(4).suppressed);

        assertTrue("broken JSON renders no rows — the JSON mode repairs it",
                ConceptTreeView.rowsOf("{broken", Collections.<String>emptyList()).isEmpty());
        assertTrue(ConceptTreeView.rowsOf("", Collections.<String>emptyList()).isEmpty());
    }
}
