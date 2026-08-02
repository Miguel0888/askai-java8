package com.aresstack.askai.research.runtime.team;

/**
 * Deterministically renders a structured {@link ExplorationMap} into GUARANTEED-VALID Mermaid mindmap source
 * (`mindmap` header, a `root((…))` node, and 2-space-per-level indented children). This is the app's
 * presentation responsibility: the model owns the ideas/hierarchy, the app owns the syntax — so a model that
 * emits an indented outline instead of Mermaid can never break the rendering. Labels are sanitized of the
 * characters that carry meaning in Mermaid node text.
 */
public final class MermaidMindmapEncoder {

    private MermaidMindmapEncoder() {
    }

    public static String encode(ExplorationMap map) {
        StringBuilder sb = new StringBuilder("mindmap\n");
        sb.append(indent(1)).append("root((").append(sanitize(map.getRoot().getLabel())).append("))\n");
        for (ExplorationNode child : map.getRoot().getChildren()) {
            append(sb, child, 2);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, ExplorationNode node, int level) {
        String label = sanitize(node.getLabel());
        if (label.isEmpty()) {
            return; // skip an empty node (and, deliberately, its subtree) rather than emit a blank line
        }
        sb.append(indent(level)).append(label).append('\n');
        for (ExplorationNode child : node.getChildren()) {
            append(sb, child, level + 1);
        }
    }

    private static String indent(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /** Drop the characters that are Mermaid node-shape/markup syntax, collapse whitespace to single spaces. */
    private static String sanitize(String label) {
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == '<' || c == '>' || c == '"' || c == '`' || c == ':' || c == '#') {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        return sb.toString().trim();
    }
}
