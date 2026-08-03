package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.domain.SourceCapture;

import java.util.ArrayList;
import java.util.List;

/**
 * Losslessly maps a host {@link VisitedCapture} (today: plain text) into the canonical domain
 * {@link SourceCapture}. It invents NO document structure it does not have: with only plain text it produces
 * neutral {@code PARAGRAPH} blocks — one per blank-line-separated paragraph (deterministic), or a single block
 * for unstructured text. When a richer browser capture later carries real heading/list/table/code structure, a
 * richer adapter replaces this one WITHOUT any change to the queue, worker or pipeline (they only see
 * {@code SourceCapture}). Pure and deterministic so it is fully testable.
 */
public final class VisitedCaptureSourceCaptureAdapter {

    private VisitedCaptureSourceCaptureAdapter() {
    }

    public static SourceCapture toSourceCapture(VisitedCapture capture, String sourceId) {
        return new SourceCapture(capture.getCaptureId(), sourceId == null ? "" : sourceId,
                capture.getCanonicalUrl(), capture.getCapturedAt(), capture.getContentHash(),
                capture.getTitle(), "", paragraphBlocks(capture.getText()));
    }

    /**
     * Deterministic minimal structure: split on blank-line boundaries into {@code PARAGRAPH} blocks; unstructured
     * text becomes a single block. Every non-blank paragraph's text is preserved verbatim (no content dropped).
     */
    static List<SourceCapture.StructuralBlock> paragraphBlocks(String text) {
        List<SourceCapture.StructuralBlock> blocks = new ArrayList<SourceCapture.StructuralBlock>();
        String body = text == null ? "" : text;
        if (body.trim().isEmpty()) {
            return blocks;
        }
        int index = 0;
        for (String paragraph : body.split("\\r?\\n[ \\t]*\\r?\\n+")) {
            String trimmed = paragraph.trim();
            if (!trimmed.isEmpty()) {
                blocks.add(new SourceCapture.StructuralBlock("block-" + index++,
                        SourceCapture.BlockKind.PARAGRAPH, "", trimmed));
            }
        }
        if (blocks.isEmpty()) {
            // Text was only inter-paragraph whitespace collapsing to nothing splittable: keep it whole.
            blocks.add(new SourceCapture.StructuralBlock("block-0",
                    SourceCapture.BlockKind.PARAGRAPH, "", body.trim()));
        }
        return blocks;
    }
}
