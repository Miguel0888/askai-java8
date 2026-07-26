package com.aresstack.askai.plugin.api.service;

/** Options for creating {@link InteractionModeControls}. Minimal for now; extend additively. */
public final class InteractionModeControlsOptions {

    private final boolean compact;

    private InteractionModeControlsOptions(boolean compact) {
        this.compact = compact;
    }

    public boolean isCompact() {
        return compact;
    }

    public static InteractionModeControlsOptions defaults() {
        return new InteractionModeControlsOptions(false);
    }

    public static InteractionModeControlsOptions compact() {
        return new InteractionModeControlsOptions(true);
    }
}
