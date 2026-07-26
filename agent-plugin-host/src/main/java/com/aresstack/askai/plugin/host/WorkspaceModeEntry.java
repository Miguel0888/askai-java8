package com.aresstack.askai.plugin.host;

/**
 * A uniform entry for the composer's mode/agent selectors. Built-in interaction modes (Yapping, Questing)
 * and installed agent plugins are represented the same way, always identified by a stable {@link #getId()} —
 * never by the display label or a combo-box index.
 */
public final class WorkspaceModeEntry {

    /** Interaction-mode ids (top-level composer selector). */
    public static final String YAPPING_ID = "builtin.yapping";
    public static final String QUESTING_ID = "builtin.questing";

    public enum Kind {
        BUILTIN,
        PLUGIN
    }

    private final String id;
    private final String displayName;
    private final Kind kind;
    private final boolean enabled;
    private final boolean selectable;
    private final int displayOrder;

    public WorkspaceModeEntry(String id, String displayName, Kind kind, boolean enabled,
                              boolean selectable, int displayOrder) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("mode id must not be empty");
        }
        this.id = id;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? id : displayName;
        this.kind = kind == null ? Kind.PLUGIN : kind;
        this.enabled = enabled;
        this.selectable = selectable;
        this.displayOrder = displayOrder;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSelectable() {
        return selectable;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceModeEntry && id.equals(((WorkspaceModeEntry) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
