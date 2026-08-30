package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A scalar property or array element: string, number, boolean or null. Terminal, no recursion. */
public final class ValueLeaf extends JsonTreeNode {

    private final JsonElement value;

    ValueLeaf(String name, JsonElement value) {
        super(name);
        this.value = value == null ? JsonNull.INSTANCE : value;
    }

    /** The scalar as a {@code JsonPrimitive}, or {@code JsonNull} for a JSON null. */
    public JsonElement getValue() {
        return value;
    }

    public boolean isNull() {
        return value.isJsonNull();
    }

    /** The scalar rendered as text ({@code "null"} for JSON null) — convenient for debug views. */
    public String asText() {
        return value.isJsonNull() ? "null"
                : value.getAsJsonPrimitive().isString()
                        ? value.getAsString() : value.toString();
    }

    @Override
    public Kind getKind() {
        return Kind.VALUE_LEAF;
    }
}
