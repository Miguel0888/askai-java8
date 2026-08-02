package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the structured {@code explorationMap} JSON ({@code { "root": string, "children": [ { "label": …,
 * "children": [...] } ] }}) into an {@link ExplorationMap}. Tolerant of missing/blank children; a missing or
 * blank root yields {@code null} (the caller then rejects the scoping turn). Bounded recursion depth guards
 * against a pathological deeply-nested payload.
 */
public final class ExplorationMapParser {

    private static final int MAX_DEPTH = 12;

    private ExplorationMapParser() {
    }

    /** @return the parsed map, or {@code null} when there is no usable root. */
    @SuppressWarnings("unchecked")
    public static ExplorationMap parse(Object rawExplorationMap) {
        if (!(rawExplorationMap instanceof Map)) {
            return null;
        }
        Map<String, Object> object = (Map<String, Object>) rawExplorationMap;
        String rootLabel = asString(object.get("root"));
        if (rootLabel == null || rootLabel.trim().isEmpty()) {
            return null;
        }
        List<ExplorationNode> children = parseChildren(object.get("children"), 1);
        return new ExplorationMap(new ExplorationNode(rootLabel, children));
    }

    @SuppressWarnings("unchecked")
    private static List<ExplorationNode> parseChildren(Object rawChildren, int depth) {
        List<ExplorationNode> out = new ArrayList<ExplorationNode>();
        if (depth > MAX_DEPTH || !(rawChildren instanceof List)) {
            return out;
        }
        for (Object element : (List<Object>) rawChildren) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) element;
            String label = asString(node.get("label"));
            if (label == null || label.trim().isEmpty()) {
                continue;
            }
            out.add(new ExplorationNode(label, parseChildren(node.get("children"), depth + 1)));
        }
        return out;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
