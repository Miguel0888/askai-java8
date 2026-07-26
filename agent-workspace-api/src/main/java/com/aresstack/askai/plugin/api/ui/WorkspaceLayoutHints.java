package com.aresstack.askai.plugin.api.ui;

/**
 * Immutable sizing hints the host uses to assemble the workspace split-pane structure. The host owns the
 * actual layout and persists divider positions per plugin and workspace.
 */
public final class WorkspaceLayoutHints {

    private final int preferredNavigationWidth;
    private final int preferredActivityWidth;
    private final int minimumMainWidth;
    private final boolean navigationInitiallyVisible;
    private final boolean activityInitiallyVisible;

    private WorkspaceLayoutHints(Builder builder) {
        this.preferredNavigationWidth = builder.preferredNavigationWidth;
        this.preferredActivityWidth = builder.preferredActivityWidth;
        this.minimumMainWidth = builder.minimumMainWidth;
        this.navigationInitiallyVisible = builder.navigationInitiallyVisible;
        this.activityInitiallyVisible = builder.activityInitiallyVisible;
    }

    public int getPreferredNavigationWidth() {
        return preferredNavigationWidth;
    }

    public int getPreferredActivityWidth() {
        return preferredActivityWidth;
    }

    public int getMinimumMainWidth() {
        return minimumMainWidth;
    }

    public boolean isNavigationInitiallyVisible() {
        return navigationInitiallyVisible;
    }

    public boolean isActivityInitiallyVisible() {
        return activityInitiallyVisible;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int preferredNavigationWidth = 260;
        private int preferredActivityWidth = 320;
        private int minimumMainWidth = 360;
        private boolean navigationInitiallyVisible = true;
        private boolean activityInitiallyVisible = true;

        public Builder preferredNavigationWidth(int value) {
            this.preferredNavigationWidth = value;
            return this;
        }

        public Builder preferredActivityWidth(int value) {
            this.preferredActivityWidth = value;
            return this;
        }

        public Builder minimumMainWidth(int value) {
            this.minimumMainWidth = value;
            return this;
        }

        public Builder navigationInitiallyVisible(boolean value) {
            this.navigationInitiallyVisible = value;
            return this;
        }

        public Builder activityInitiallyVisible(boolean value) {
            this.activityInitiallyVisible = value;
            return this;
        }

        public WorkspaceLayoutHints build() {
            return new WorkspaceLayoutHints(this);
        }
    }
}
