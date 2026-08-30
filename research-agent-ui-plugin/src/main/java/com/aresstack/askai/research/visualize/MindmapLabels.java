package com.aresstack.askai.research.visualize;

/**
 * The ONE mindmap label sanitizer (hoisted out of {@link SourceMindmap} so every mechanical
 * Mermaid builder shares it): the mindmap grammar is indentation-based with shape brackets, so
 * brackets, parentheses, quotes and line breaks are stripped, whitespace collapsed and the
 * length capped — labels can never break the diagram syntax.
 */
public final class MindmapLabels {

    private MindmapLabels() {
    }

    public static String sanitize(String text, String fallback, int maxChars) {
        String value = text == null ? "" : text;
        value = value.replaceAll("[\\[\\](){}\"'`\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            value = fallback == null ? "?" : sanitize(fallback, "?", maxChars);
        }
        if (value.length() > maxChars) {
            value = value.substring(0, maxChars - 1).trim() + "…";
        }
        return value;
    }
}
