package com.aresstack.askai.research.visualize;

import com.aresstack.askai.agent.model.reranker.MiniJson;

import java.util.Map;

/**
 * Parses the visualizer model's raw text into a {@link VisualizationResult}. Tolerant and NON-throwing: any
 * malformed, message-less or {@code decision != DIAGRAM} answer degrades to {@link VisualizationResult#none}
 * (a visualization failure is never critical). A DIAGRAM needs a non-blank {@code mermaid}; the diagram type
 * is read leniently ({@link VisualizationType#fromToken}).
 */
public final class VisualizationResultParser {

    private VisualizationResultParser() {
    }

    @SuppressWarnings("unchecked")
    public static VisualizationResult parse(String rawModelText) {
        String json = extractJsonObject(rawModelText);
        if (json == null) {
            return VisualizationResult.failed("no JSON object in the visualizer answer");
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (RuntimeException malformed) {
            return VisualizationResult.failed("visualizer answer was not valid JSON");
        }
        if (!(root instanceof Map)) {
            return VisualizationResult.failed("visualizer answer is not a JSON object");
        }
        Map<String, Object> object = (Map<String, Object>) root;
        String decision = asString(object.get("decision"));
        if (decision == null || !decision.trim().equalsIgnoreCase("DIAGRAM")) {
            // A deliberate model choice of no diagram — a valid NONE, not a failure.
            String reason = asString(object.get("reason"));
            return VisualizationResult.none(reason == null ? "the model chose no diagram" : reason);
        }
        String mermaid = asString(object.get("mermaid"));
        if (mermaid == null || mermaid.trim().isEmpty()) {
            return VisualizationResult.failed("DIAGRAM decision without a diagram source");
        }
        return VisualizationResult.diagram(
                VisualizationType.fromToken(asString(object.get("diagramType"))),
                asString(object.get("title")),
                mermaid);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** Extract the first balanced top-level {@code {...}} object, ignoring surrounding prose or code fences. */
    static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced — treat as no object
    }
}
