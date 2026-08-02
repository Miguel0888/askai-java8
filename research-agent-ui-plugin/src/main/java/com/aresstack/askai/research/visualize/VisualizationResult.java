package com.aresstack.askai.research.visualize;

/**
 * The two-stage outcome of visualizing an artifact: either NONE (a fully valid result — "nothing useful to
 * visualize here yet") or a DIAGRAM (type + title + Mermaid source). Deciding NONE is explicitly encouraged
 * over inventing a diagram, so a barely-structured artifact (e.g. a one-line first brief) is not forced into a
 * fake taxonomy.
 */
public final class VisualizationResult {

    private final boolean present;
    private final VisualizationType type;
    private final String title;
    private final String mermaid;
    private final String reason;

    private VisualizationResult(boolean present, VisualizationType type, String title, String mermaid,
                                String reason) {
        this.present = present;
        this.type = type;
        this.title = title == null ? "" : title;
        this.mermaid = mermaid == null ? "" : mermaid;
        this.reason = reason == null ? "" : reason;
    }

    /** No useful visualization — a valid outcome. */
    public static VisualizationResult none(String reason) {
        return new VisualizationResult(false, null, "", "", reason);
    }

    /** A diagram of the given type, title and Mermaid source (source must be non-blank). */
    public static VisualizationResult diagram(VisualizationType type, String title, String mermaid) {
        if (type == null) {
            throw new IllegalArgumentException("diagram type must not be null");
        }
        if (mermaid == null || mermaid.trim().isEmpty()) {
            throw new IllegalArgumentException("diagram mermaid must not be blank");
        }
        return new VisualizationResult(true, type, title, mermaid.trim(), "");
    }

    /** True when a diagram is present; false for NONE. */
    public boolean isPresent() {
        return present;
    }

    /** The diagram type, or {@code null} for NONE. */
    public VisualizationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    /** The Mermaid source for a diagram; empty for NONE. */
    public String getMermaid() {
        return mermaid;
    }

    /** Why NONE was chosen (empty for a diagram). */
    public String getReason() {
        return reason;
    }
}
