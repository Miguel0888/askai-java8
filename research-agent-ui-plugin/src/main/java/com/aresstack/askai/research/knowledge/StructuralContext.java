package com.aresstack.askai.research.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The document-structure context of a passage (§8.4, §9): the heading path leading to it and the kind of block
 * it came from. Structure is a SECONDARY signal for segmentation (semantics is primary); a hard block boundary
 * (heading/table/list/code/section) may still force a passage break. Absent structure is an empty heading path
 * and {@link BlockKind#UNKNOWN}, never null.
 */
public final class StructuralContext {

    /** The kinds of document block a passage may originate from; a change is a candidate hard boundary. */
    public enum BlockKind {
        UNKNOWN, PARAGRAPH, HEADING, LIST, TABLE, CODE, QUOTE
    }

    public static final StructuralContext NONE =
            new StructuralContext(Collections.<String>emptyList(), BlockKind.UNKNOWN);

    private final List<String> headingPath;
    private final BlockKind blockKind;

    public StructuralContext(List<String> headingPath, BlockKind blockKind) {
        this.headingPath = Collections.unmodifiableList(
                new ArrayList<String>(headingPath == null ? Collections.<String>emptyList() : headingPath));
        this.blockKind = blockKind == null ? BlockKind.UNKNOWN : blockKind;
    }

    public List<String> getHeadingPath() {
        return headingPath;
    }

    public BlockKind getBlockKind() {
        return blockKind;
    }
}
