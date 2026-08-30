package com.aresstack.askai.research.jsontree;

import java.util.Collections;
import java.util.List;

/**
 * A property whose JSON value is an array — the only STRUCTURAL node kind. An ArrayNode with zero
 * children ({@code "Tasks": []}) is deliberately still a full structural node: it stays visible in
 * the structural/mindmap projection and remains a valid edit target later.
 */
public final class ArrayNode extends JsonTreeNode {

    private final List<JsonTreeNode> children;

    ArrayNode(String name, List<JsonTreeNode> children) {
        super(name);
        this.children = Collections.unmodifiableList(children);
    }

    /** All children in document order; container objects inside the array are already flattened. */
    public List<JsonTreeNode> getChildren() {
        return children;
    }

    @Override
    public Kind getKind() {
        return Kind.ARRAY;
    }
}
