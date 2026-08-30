package com.aresstack.askai.research.jsontree;

import com.aresstack.askai.research.visualize.MindmapLabels;

/**
 * The ONLY Mermaid-coupled piece of this layer: renders a {@link StructuralForest} as a Mermaid
 * {@code mindmap}. The forest itself stays render-neutral, so other presentations (Java2D, debug
 * tree, …) can be added beside this one. Assembly is purely mechanical — structure comes from
 * DATA, labels go through the shared sanitizer, so the diagram always parses.
 */
public final class MermaidMindmapRenderer {

    private static final int MAX_LABEL_CHARS = 48;

    private MermaidMindmapRenderer() {
    }

    /**
     * @param rootTitle the label of the synthetic single root Mermaid requires; the forest's
     *                  roots become its first-level branches. An empty forest renders as the
     *                  bare root — an honest "no structure yet" diagram, never an error.
     */
    public static String render(StructuralForest forest, String rootTitle) {
        StringBuilder out = new StringBuilder("mindmap\n");
        out.append("  root((").append(label(rootTitle)).append("))\n");
        for (StructuralNode root : forest.getRoots()) {
            renderNode(out, root, 2);
        }
        return out.toString();
    }

    private static void renderNode(StringBuilder out, StructuralNode node, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        out.append('(').append(label(node.getName())).append(")\n");
        for (StructuralNode child : node.getChildren()) {
            renderNode(out, child, depth + 1);
        }
    }

    private static String label(String text) {
        return MindmapLabels.sanitize(text, "?", MAX_LABEL_CHARS);
    }
}
