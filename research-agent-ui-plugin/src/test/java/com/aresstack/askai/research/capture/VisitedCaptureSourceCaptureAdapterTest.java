package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The VisitedCapture → domain SourceCapture bridge: lossless text, deterministic PARAGRAPH blocks, no invented
 *  structure. */
public class VisitedCaptureSourceCaptureAdapterTest {

    private static VisitedCapture capture(String text) {
        return new VisitedCapture("cap-1", "https://x/y", "https://x/y", "Title", text, "hash", 42L,
                null, null, null);
    }

    @Test
    public void unstructuredTextBecomesOneParagraphBlockVerbatim() {
        String text = "Wheat flour and rye flour differ in gluten. Type 405 is finely milled.";
        SourceCapture sc = VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture(text), "src-1");
        assertEquals("cap-1", sc.getCaptureId());
        assertEquals("src-1", sc.getSourceId());
        assertEquals("Title", sc.getTitle());
        assertEquals(1, sc.getBlocks().size());
        assertEquals(SourceCapture.BlockKind.PARAGRAPH, sc.getBlocks().get(0).getKind());
        assertEquals("text preserved verbatim", text, sc.getBlocks().get(0).getText());
    }

    @Test
    public void blankLineSeparatedTextBecomesMultipleDeterministicParagraphBlocks() {
        String text = "First paragraph about wheat.\n\nSecond paragraph about rye.\n\n\nThird one.";
        SourceCapture sc = VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture(text), "src-1");
        List<SourceCapture.StructuralBlock> blocks = sc.getBlocks();
        assertEquals(3, blocks.size());
        assertEquals("block-0", blocks.get(0).getBlockId());
        assertEquals("First paragraph about wheat.", blocks.get(0).getText());
        assertEquals("Second paragraph about rye.", blocks.get(1).getText());
        assertEquals("Third one.", blocks.get(2).getText());
        for (SourceCapture.StructuralBlock b : blocks) {
            assertEquals("only neutral PARAGRAPH, no invented structure",
                    SourceCapture.BlockKind.PARAGRAPH, b.getKind());
        }
    }

    @Test
    public void noTextIsLostAcrossTheBlocks() {
        String text = "A. \n\n B.  \n\n\n  C.";
        SourceCapture sc = VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture(text), "src-1");
        StringBuilder joined = new StringBuilder();
        for (SourceCapture.StructuralBlock b : sc.getBlocks()) {
            joined.append(b.getText());
        }
        // Every non-whitespace character of the original survives in some block, in order.
        String originalCompact = text.replaceAll("\\s+", "");
        String joinedCompact = joined.toString().replaceAll("\\s+", "");
        assertEquals(originalCompact, joinedCompact);
    }

    @Test
    public void deterministic() {
        String text = "One.\n\nTwo.";
        SourceCapture a = VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture(text), "src-1");
        SourceCapture b = VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture(text), "src-1");
        assertEquals(a.getBlocks().size(), b.getBlocks().size());
        for (int i = 0; i < a.getBlocks().size(); i++) {
            assertEquals(a.getBlocks().get(i).getBlockId(), b.getBlocks().get(i).getBlockId());
            assertEquals(a.getBlocks().get(i).getText(), b.getBlocks().get(i).getText());
        }
    }

    @Test
    public void blankTextYieldsNoBlocks() {
        assertTrue(VisitedCaptureSourceCaptureAdapter.paragraphBlocks("   \n\n  ").isEmpty());
    }
}
