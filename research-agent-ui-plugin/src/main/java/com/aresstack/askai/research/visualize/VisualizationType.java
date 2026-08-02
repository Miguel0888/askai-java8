package com.aresstack.askai.research.visualize;

import java.util.Locale;

/**
 * The kind of diagram a visualization may take. Chosen by the visualizer FROM THE ARTIFACT's structure, never
 * wired to a phase — e.g. a mindmap is only appropriate when the artifact genuinely contains a hierarchy. The
 * "no diagram" outcome is NOT a type here; it is {@link VisualizationResult#none(String)}.
 */
public enum VisualizationType {

    FLOWCHART,
    MINDMAP,
    TIMELINE,
    SEQUENCE,
    CLASS_DIAGRAM,
    GRAPH;

    /** Parse a type token case-insensitively ({@code -}/space tolerant); an unknown token falls back to GRAPH. */
    public static VisualizationType fromToken(String token) {
        if (token != null) {
            String normalized = token.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (VisualizationType type : values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
        }
        return GRAPH; // a neutral, generic default rather than forcing a taxonomy-implying mindmap
    }
}
