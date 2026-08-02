package com.aresstack.askai.research.runtime.team;

/**
 * The scoping exploration map as STRUCTURE (a root idea + a tree of sub-ideas), owned by the model. The model
 * decides the content and hierarchy; the application deterministically renders it (see
 * {@link MermaidMindmapEncoder}) so a weak model can never emit broken Mermaid. The root label must be
 * non-blank; children may be empty.
 */
public final class ExplorationMap {

    private final ExplorationNode root;

    public ExplorationMap(ExplorationNode root) {
        if (root == null || root.getLabel().isEmpty()) {
            throw new IllegalArgumentException("exploration map needs a non-blank root");
        }
        this.root = root;
    }

    public ExplorationNode getRoot() {
        return root;
    }
}
