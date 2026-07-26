package com.aresstack.askai.plugin.api.service;

/** Immutable options for creating a {@link ConversationSurface}. Extend additively. */
public final class ConversationSurfaceOptions {

    private final boolean showThinking;
    private final boolean showToolActivity;

    private ConversationSurfaceOptions(Builder builder) {
        this.showThinking = builder.showThinking;
        this.showToolActivity = builder.showToolActivity;
    }

    public boolean isShowThinking() {
        return showThinking;
    }

    public boolean isShowToolActivity() {
        return showToolActivity;
    }

    public static ConversationSurfaceOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean showThinking = true;
        private boolean showToolActivity = true;

        public Builder showThinking(boolean value) {
            this.showThinking = value;
            return this;
        }

        public Builder showToolActivity(boolean value) {
            this.showToolActivity = value;
            return this;
        }

        public ConversationSurfaceOptions build() {
            return new ConversationSurfaceOptions(this);
        }
    }
}
