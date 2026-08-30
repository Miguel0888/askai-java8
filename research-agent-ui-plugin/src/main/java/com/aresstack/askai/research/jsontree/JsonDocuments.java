package com.aresstack.askai.research.jsontree;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

/** The one serializer of this layer: plain JSON, no HTML escaping, deterministic output. */
final class JsonDocuments {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonDocuments() {
    }

    static String write(JsonElement element) {
        return GSON.toJson(element);
    }
}
