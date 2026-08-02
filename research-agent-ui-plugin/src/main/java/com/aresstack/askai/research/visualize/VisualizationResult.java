package com.aresstack.askai.research.visualize;

/**
 * The outcome of visualizing an artifact, in THREE distinguishable kinds so "the model decided nothing useful"
 * is never confused with "the visualizer could not produce a result":
 * <ul>
 *   <li>{@link Kind#DIAGRAM} — a diagram (type + title + Mermaid);</li>
 *   <li>{@link Kind#NONE} — the model deliberately chose no diagram (a valid, good outcome);</li>
 *   <li>{@link Kind#FAILED} — no result could be produced (no inference port, timeout, transport failure,
 *       or unparseable output).</li>
 * </ul>
 */
public final class VisualizationResult {

    public enum Kind {
        DIAGRAM,
        NONE,
        FAILED
    }

    private final Kind kind;
    private final VisualizationType type;
    private final String title;
    private final String mermaid;
    private final String reason;

    private VisualizationResult(Kind kind, VisualizationType type, String title, String mermaid,
                                String reason) {
        this.kind = kind;
        this.type = type;
        this.title = title == null ? "" : title;
        this.mermaid = mermaid == null ? "" : mermaid;
        this.reason = reason == null ? "" : reason;
    }

    /** The model deliberately chose no diagram — a valid outcome. */
    public static VisualizationResult none(String reason) {
        return new VisualizationResult(Kind.NONE, null, "", "", reason);
    }

    /** No result could be produced (no port, timeout, transport failure, unparseable output). */
    public static VisualizationResult failed(String reason) {
        return new VisualizationResult(Kind.FAILED, null, "", "", reason);
    }

    /** A diagram of the given type, title and Mermaid source (source must be non-blank). */
    public static VisualizationResult diagram(VisualizationType type, String title, String mermaid) {
        if (type == null) {
            throw new IllegalArgumentException("diagram type must not be null");
        }
        if (mermaid == null || mermaid.trim().isEmpty()) {
            throw new IllegalArgumentException("diagram mermaid must not be blank");
        }
        return new VisualizationResult(Kind.DIAGRAM, type, title, mermaid.trim(), "");
    }

    public Kind getKind() {
        return kind;
    }

    /** True only when a diagram is present. */
    public boolean isPresent() {
        return kind == Kind.DIAGRAM;
    }

    /** The diagram type, or {@code null} unless {@link #isPresent()}. */
    public VisualizationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    /** The Mermaid source for a diagram; empty otherwise. */
    public String getMermaid() {
        return mermaid;
    }

    /** Why NONE or FAILED (empty for a diagram). */
    public String getReason() {
        return reason;
    }
}
