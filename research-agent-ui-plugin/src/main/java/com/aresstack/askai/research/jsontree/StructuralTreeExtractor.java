package com.aresstack.askai.research.jsontree;

import java.util.ArrayList;
import java.util.List;

/**
 * STRUCTURAL PROJECTION layer: reduces a typed {@link JsonTree} to its {@link ArrayNode}s only.
 * ValueLeafs and ObjectLeafs disappear; empty ArrayNodes explicitly do NOT — the filter is the
 * node KIND, never the child count, because an empty array is a valid future edit target.
 */
public final class StructuralTreeExtractor {

    private StructuralTreeExtractor() {
    }

    public static StructuralForest extract(JsonTree tree) {
        List<StructuralNode> roots = new ArrayList<StructuralNode>();
        for (JsonTreeNode node : tree.getRoots()) {
            if (node instanceof ArrayNode) {
                roots.add(project((ArrayNode) node));
            }
        }
        return new StructuralForest(roots);
    }

    private static StructuralNode project(ArrayNode node) {
        List<StructuralNode> children = new ArrayList<StructuralNode>();
        for (JsonTreeNode child : node.getChildren()) {
            if (child instanceof ArrayNode) {
                children.add(project((ArrayNode) child));
            }
        }
        return new StructuralNode(node.getName(), children);
    }
}
