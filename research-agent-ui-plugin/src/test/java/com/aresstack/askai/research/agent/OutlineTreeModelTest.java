package com.aresstack.askai.research.agent;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #43 slice 8 — the outline projection layer: chapter numbers 1 / 1.1 / 1.1.1 in stable
 * document order, bullets as unnumbered annotations under their chapter, title and italic
 * meta lines skipped. A projection only — no outline semantics, no staleness.
 */
public class OutlineTreeModelTest {

    @Test
    public void chaptersAreNumberedHierarchicallyInStableOrder() {
        List<OutlineTreeModel.Row> rows = OutlineTreeModel.parse(
                "# Live Outline\n\n"
                        + "_Automatisch abgeleitet — keine freigegebene Gliederung._\n\n"
                        + "## Betriebssysteme\n"
                        + "### Linux\n"
                        + "#### Scheduling\n"
                        + "### FreeRTOS\n"
                        + "## Werkzeuge\n");
        assertEquals(5, rows.size());
        assertRow(rows.get(0), "1", "Betriebssysteme", 0);
        assertRow(rows.get(1), "1.1", "Linux", 1);
        assertRow(rows.get(2), "1.1.1", "Scheduling", 2);
        assertRow(rows.get(3), "1.2", "FreeRTOS", 1);
        assertRow(rows.get(4), "2", "Werkzeuge", 0);
    }

    @Test
    public void bulletsBecomeUnnumberedAnnotationsUnderTheirChapter() {
        List<OutlineTreeModel.Row> rows = OutlineTreeModel.parse(
                "## Scheduling\n"
                        + "_3 Passagen_\n"
                        + "- offene Frage: Wie greift Preemption?\n"
                        + "## GUI\n");
        assertEquals(3, rows.size());
        assertRow(rows.get(0), "1", "Scheduling", 0);
        OutlineTreeModel.Row note = rows.get(1);
        assertTrue(note.annotation);
        assertEquals("", note.number);
        assertEquals("offene Frage: Wie greift Preemption?", note.title);
        assertEquals("annotations sit one deeper than their chapter", 1, note.depth);
        assertRow(rows.get(2), "2", "GUI", 0);
    }

    @Test
    public void theLiveProjectionHeaderAndMetaLinesAreNotStructure() {
        List<OutlineTreeModel.Row> rows = OutlineTreeModel.parse(
                "# Live Outline\n"
                        + "_Revision 3 · 2 Themen · 12 Passagen_\n"
                        + "\n_Noch keine verarbeiteten Quellen._\n");
        assertTrue("meta-only outlines yield no rows", rows.isEmpty());
        assertTrue(OutlineTreeModel.parse("").isEmpty());
        assertTrue(OutlineTreeModel.parse(null).isEmpty());
    }

    private static void assertRow(OutlineTreeModel.Row row, String number, String title,
                                  int depth) {
        assertEquals(number, row.number);
        assertEquals(title, row.title);
        assertEquals(depth, row.depth);
        assertTrue(!row.annotation);
    }
}
