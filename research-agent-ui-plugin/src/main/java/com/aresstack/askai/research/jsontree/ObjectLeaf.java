package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonObject;

/**
 * A property whose JSON value is an OBJECT. For this tree projection the object is a terminal,
 * fully opaque value — the parser never recurses into it, so arrays nested inside it do NOT
 * become structural nodes ({@code "settings": {"profiles": []}} is just the leaf "settings").
 */
public final class ObjectLeaf extends JsonTreeNode {

    private final JsonObject value;

    ObjectLeaf(String name, JsonObject value) {
        super(name);
        this.value = value;
    }

    /** The opaque object value. Treat as read-only — it aliases the parsed document. */
    public JsonObject getValue() {
        return value;
    }

    @Override
    public Kind getKind() {
        return Kind.OBJECT_LEAF;
    }
}
