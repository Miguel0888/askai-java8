package com.aresstack.askai.research.concept;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The MECHANICAL half of the one-command exclusion facade (live-gate 4): given the concept
 * document and an excluded topic, find the EXACT-name concept card the topic conflicts with.
 * Deliberately dumb — exact (case-insensitive, trimmed) name equality only; semantic/embedding
 * similarity is a later, separate step and NEVER deletes anything by itself.
 */
public final class ConceptTopicScanner {

    private ConceptTopicScanner() {
    }

    /**
     * The card-name path (segments from the concept root) of the FIRST card whose name equals
     * {@code topic}, or {@code null} when the concept has no such card or is unreadable. The
     * path is exactly what {@link ConceptBranchService#removeNodeAt(List)} addresses — the
     * caller can keep it behind an opaque conflictId and never hand it to the model.
     */
    public static List<String> findExactPath(String documentJson, String topic) {
        if (documentJson == null || topic == null || topic.trim().isEmpty()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(documentJson);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement concept = root.getAsJsonObject().get("concept");
            if (concept == null || !concept.isJsonArray()) {
                return null;
            }
            return walk(concept.getAsJsonArray(), topic.trim(), new ArrayList<String>());
        } catch (RuntimeException unreadable) {
            return null; // an unreadable concept is diagnosed elsewhere — never a crash here
        }
    }

    /**
     * ALL card-name paths of the concept, in document order (Zielbild slice 1: the mindmap IS
     * the positive working space — its cards become the fence's IN posts). Unreadable or
     * concept-less documents yield an empty list, never a crash.
     */
    public static List<List<String>> collectCardPaths(String documentJson) {
        List<List<String>> paths = new ArrayList<List<String>>();
        if (documentJson == null) {
            return paths;
        }
        try {
            JsonElement root = JsonParser.parseString(documentJson);
            if (!root.isJsonObject()) {
                return paths;
            }
            JsonElement concept = root.getAsJsonObject().get("concept");
            if (concept == null || !concept.isJsonArray()) {
                return paths;
            }
            collect(concept.getAsJsonArray(), new ArrayList<String>(), paths);
        } catch (RuntimeException unreadable) {
            paths.clear();
        }
        return paths;
    }

    private static void collect(JsonArray array, List<String> path, List<List<String>> out) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> card : element.getAsJsonObject().entrySet()) {
                List<String> here = new ArrayList<String>(path);
                here.add(card.getKey());
                out.add(here);
                if (card.getValue().isJsonArray()) {
                    collect(card.getValue().getAsJsonArray(), here, out);
                }
            }
        }
    }

    /** Three-node rule: objects in arrays are invisible containers; their KEYS are the cards. */
    private static List<String> walk(JsonArray array, String topic, List<String> path) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> card : element.getAsJsonObject().entrySet()) {
                List<String> here = new ArrayList<String>(path);
                here.add(card.getKey());
                if (card.getKey().trim().equalsIgnoreCase(topic)) {
                    return here;
                }
                if (card.getValue().isJsonArray()) {
                    List<String> below = walk(card.getValue().getAsJsonArray(), topic, here);
                    if (below != null) {
                        return below;
                    }
                }
            }
        }
        return null;
    }
}
