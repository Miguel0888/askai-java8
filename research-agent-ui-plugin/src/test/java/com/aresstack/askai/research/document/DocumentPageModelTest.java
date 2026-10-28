package com.aresstack.askai.research.document;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #43 slice 9 — the Document page projection: markdown becomes typed blocks (heading levels +
 * flowing paragraphs), and inline {@code [n]} source markers surface as TYPED references in
 * first-appearance order — the contract #41's citation registry couples to.
 */
public class DocumentPageModelTest {

    @Test
    public void markdownBecomesTypedBlocksWithFlowingParagraphs() {
        List<DocumentPageModel.Block> blocks = DocumentPageModel.parse(
                "# 1. Betriebssysteme\n"
                        + "\n"
                        + "Erster Satz [1].\n"
                        + "Zweiter Satz derselben Zeile [2].\n"
                        + "\n"
                        + "## 1.1 Linux\n"
                        + "Nur Text ohne Marker.\n");
        assertEquals(4, blocks.size());
        assertEquals(DocumentPageModel.Kind.HEADING_1, blocks.get(0).kind);
        assertEquals("1. Betriebssysteme", blocks.get(0).text);
        assertEquals(DocumentPageModel.Kind.PARAGRAPH, blocks.get(1).kind);
        assertEquals("adjacent lines flow into ONE paragraph",
                "Erster Satz [1]. Zweiter Satz derselben Zeile [2].", blocks.get(1).text);
        assertEquals(Arrays.asList(1, 2), blocks.get(1).references);
        assertEquals(DocumentPageModel.Kind.HEADING_2, blocks.get(2).kind);
        assertTrue(blocks.get(3).references.isEmpty());
    }

    @Test
    public void referenceMarkersAreTypedDistinctAndInFirstAppearanceOrder() {
        assertEquals(Arrays.asList(3, 1),
                DocumentPageModel.referencesIn("Siehe [3], dann [1], nochmal [3]."));
        assertTrue(DocumentPageModel.referencesIn("Kein Marker [abc] [12345]").isEmpty());
    }

    @Test
    public void emptyDocumentsYieldNoBlocks() {
        assertTrue(DocumentPageModel.parse("").isEmpty());
        assertTrue(DocumentPageModel.parse(null).isEmpty());
        assertTrue(DocumentPageModel.parse("\n\n").isEmpty());
    }
}
