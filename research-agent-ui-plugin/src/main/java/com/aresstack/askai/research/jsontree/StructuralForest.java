package com.aresstack.askai.research.jsontree;

import java.util.Collections;
import java.util.List;

/**
 * The array-only view of a document: every top-level {@link ArrayNode} becomes one root. This is
 * the render-neutral input for ALL later mindmap presentations (Mermaid, Java2D, debug tree, …) —
 * deliberately free of any Mermaid coupling.
 */
public final class StructuralForest {

    private final List<StructuralNode> roots;

    StructuralForest(List<StructuralNode> roots) {
        this.roots = Collections.unmodifiableList(roots);
    }

    /**
     * A forest from explicit roots — e.g. the CHILDREN of one node, when a view wants to show a
     * section's content without the section wrapper itself (the Konzept tab shows the concept's
     * cards, not a "concept" node).
     */
    public static StructuralForest of(List<StructuralNode> roots) {
        return new StructuralForest(new java.util.ArrayList<StructuralNode>(roots));
    }

    public List<StructuralNode> getRoots() {
        return roots;
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }
}
