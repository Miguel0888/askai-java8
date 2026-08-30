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

    public List<StructuralNode> getRoots() {
        return roots;
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }
}
