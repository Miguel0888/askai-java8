package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.List;

/**
 * The typed tree projection of ONE parsed JSON document. The projection is read-only and derived:
 * the raw {@link #getDocument() document element} stays the storage truth and is never altered by
 * building this view. Top level: a root OBJECT contributes one node per property (it is a
 * container, like an object inside an array); a root ARRAY becomes a single anonymous
 * {@link ArrayNode}; a root SCALAR a single anonymous {@link ValueLeaf}.
 */
public final class JsonTree {

    private final JsonElement document;
    private final List<JsonTreeNode> roots;

    JsonTree(JsonElement document, List<JsonTreeNode> roots) {
        this.document = document;
        this.roots = Collections.unmodifiableList(roots);
    }

    /** The untouched raw document element this tree was projected from. Treat as read-only. */
    public JsonElement getDocument() {
        return document;
    }

    /** The top-level nodes in document order (empty for an empty root object). */
    public List<JsonTreeNode> getRoots() {
        return roots;
    }
}
