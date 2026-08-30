package com.aresstack.askai.research.jsontree;

import java.util.Collections;
import java.util.List;

/**
 * One node of the STRUCTURAL projection: only {@link ArrayNode}s survive it, so a StructuralNode
 * is always "an array-valued property" (or an anonymous nested array). The filter criterion is
 * the ORIGINAL node kind, never {@code children.isEmpty()} — an empty array stays a node.
 */
public final class StructuralNode {

    private final String name;
    private final List<StructuralNode> children;

    StructuralNode(String name, List<StructuralNode> children) {
        this.name = name;
        this.children = Collections.unmodifiableList(children);
    }

    /** The property name, or {@code null} for an anonymous array (an array inside an array). */
    public String getName() {
        return name;
    }

    public List<StructuralNode> getChildren() {
        return children;
    }
}
