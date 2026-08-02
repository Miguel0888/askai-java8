package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One node of the exploration map: a label plus optional child nodes. Pure content — no Mermaid syntax. */
public final class ExplorationNode {

    private final String label;
    private final List<ExplorationNode> children;

    public ExplorationNode(String label, List<ExplorationNode> children) {
        this.label = label == null ? "" : label.trim();
        this.children = children == null
                ? Collections.<ExplorationNode>emptyList()
                : Collections.unmodifiableList(new ArrayList<ExplorationNode>(children));
    }

    public String getLabel() {
        return label;
    }

    public List<ExplorationNode> getChildren() {
        return children;
    }
}
