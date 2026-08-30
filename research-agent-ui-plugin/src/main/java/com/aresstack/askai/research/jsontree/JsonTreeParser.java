package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TREE SEMANTICS layer: applies the interpretation rule to a strictly parsed document. Array
 * property → structural {@link ArrayNode} (recurse); object property → opaque {@link ObjectLeaf}
 * (stop); scalar property → {@link ValueLeaf} (stop). Objects that are ELEMENTS of an open array
 * are invisible containers whose properties become the array's children. Works on ANY valid JSON
 * (root object/array/scalar, arbitrary nesting) and never mutates the parsed document.
 */
public final class JsonTreeParser {

    private JsonTreeParser() {
    }

    /** Strict-parse the text and project it; syntax failures surface as the strict diagnostic. */
    public static JsonTreeParseResult parse(String jsonText) {
        StrictJsonParseResult parsed = StrictJsonParser.parse(jsonText);
        if (!parsed.isOk()) {
            return JsonTreeParseResult.error(parsed.getDiagnostic());
        }
        return JsonTreeParseResult.ok(project(parsed.getElement()));
    }

    /** Project an already-parsed document (used for candidates that exist only in memory). */
    public static JsonTree project(JsonElement document) {
        List<JsonTreeNode> roots = new ArrayList<JsonTreeNode>();
        if (document.isJsonObject()) {
            // The root object is a container like an object inside an array: one node per property.
            for (Map.Entry<String, JsonElement> property : document.getAsJsonObject().entrySet()) {
                roots.add(interpretProperty(property.getKey(), property.getValue()));
            }
        } else if (document.isJsonArray()) {
            roots.add(arrayNode(null, document.getAsJsonArray()));
        } else {
            roots.add(new ValueLeaf(null, document));
        }
        return new JsonTree(document, roots);
    }

    /** The central rule: only an ARRAY value makes a structural node; everything else is a leaf. */
    private static JsonTreeNode interpretProperty(String name, JsonElement value) {
        if (value.isJsonArray()) {
            return arrayNode(name, value.getAsJsonArray());
        }
        if (value.isJsonObject()) {
            return new ObjectLeaf(name, value.getAsJsonObject());
        }
        return new ValueLeaf(name, value);
    }

    private static ArrayNode arrayNode(String name, JsonArray array) {
        List<JsonTreeNode> children = new ArrayList<JsonTreeNode>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                // A container, not a visible node: its properties become children of THIS array.
                for (Map.Entry<String, JsonElement> property
                        : element.getAsJsonObject().entrySet()) {
                    children.add(interpretProperty(property.getKey(), property.getValue()));
                }
            } else if (element.isJsonArray()) {
                children.add(arrayNode(null, element.getAsJsonArray()));
            } else {
                children.add(new ValueLeaf(null, element));
            }
        }
        return new ArrayNode(name, children);
    }
}
