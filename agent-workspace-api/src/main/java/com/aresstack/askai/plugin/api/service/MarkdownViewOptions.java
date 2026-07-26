package com.aresstack.askai.plugin.api.service;

/** Immutable options for creating a {@link MarkdownView}. Kept minimal; extend additively. */
public final class MarkdownViewOptions {

    private final boolean renderMermaid;
    private final boolean selectable;

    private MarkdownViewOptions(Builder builder) {
        this.renderMermaid = builder.renderMermaid;
        this.selectable = builder.selectable;
    }

    public boolean isRenderMermaid() {
        return renderMermaid;
    }

    public boolean isSelectable() {
        return selectable;
    }

    public static MarkdownViewOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean renderMermaid = true;
        private boolean selectable = true;

        public Builder renderMermaid(boolean value) {
            this.renderMermaid = value;
            return this;
        }

        public Builder selectable(boolean value) {
            this.selectable = value;
            return this;
        }

        public MarkdownViewOptions build() {
            return new MarkdownViewOptions(this);
        }
    }
}
