package com.aresstack.askai.plugin.host;

/**
 * One entry in the chat mode selector: a stable id plus a human-readable display name. Selection and
 * persistence use {@link #getId()} only — never the display name or a combo-box index — so duplicate display
 * names stay unambiguous and a renamed/localized label never breaks the remembered choice.
 */
public final class WorkspaceMode {

    /** The built-in, always-present normal chat mode. */
    public static final String NORMAL_CHAT_ID = "builtin.normal-chat";

    private final String id;
    private final String displayName;

    public WorkspaceMode(String id, String displayName) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("mode id must not be empty");
        }
        this.id = id;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? id : displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isNormalChat() {
        return NORMAL_CHAT_ID.equals(id);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceMode && id.equals(((WorkspaceMode) other).id);
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
