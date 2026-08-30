package com.aresstack.askai.research.jsontree;

/**
 * One node of the typed JSON tree projection. The central interpretation rule of this layer:
 * a JSON property whose value is an ARRAY is a structural {@link ArrayNode} (the only kind that
 * recurses); a property whose value is an OBJECT is an opaque {@link ObjectLeaf} (never entered);
 * a scalar property (string/number/boolean/null) is a {@link ValueLeaf}. Objects that appear as
 * ELEMENTS of an already-opened array are mere containers — their properties become siblings under
 * the array, the container itself is never a visible node. Anonymous nodes (array elements without
 * a property name: nested arrays, scalars in arrays, whole-document roots) carry a {@code null}
 * name. No Swing, no research-domain types — this layer is generic.
 */
public abstract class JsonTreeNode {

    /** The three semantic node kinds; only {@link #ARRAY} opens recursion. */
    public enum Kind { ARRAY, OBJECT_LEAF, VALUE_LEAF }

    private final String name;

    JsonTreeNode(String name) {
        this.name = name;
    }

    /** The property name, or {@code null} for an anonymous node (array element / document root). */
    public final String getName() {
        return name;
    }

    public final boolean isAnonymous() {
        return name == null;
    }

    public abstract Kind getKind();
}
