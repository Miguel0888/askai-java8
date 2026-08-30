package com.aresstack.askai.plugin.api.agent.composer;

import javax.swing.JComponent;

/**
 * A persistent Swing component an agent plugin places directly above or below the chat composer (not in the
 * artifact sidepanel; see {@link Placement}), visible while its agent session is the active one. The host owns the component's placement and
 * calls {@link #dispose()} exactly once when the session/agent/tab changes or the session closes — an explicit
 * lifecycle, so the component may be removed from and re-added to the layout without losing its listeners.
 * Every method runs on the EDT.
 */
public interface ComposerAccessory {

    /** Which side of the composer the accessory occupies. */
    enum Placement {
        /** Directly above the composer (the default) — e.g. the scoping tag surface. */
        ABOVE_COMPOSER,
        /** Directly below the composer — e.g. a phase-bound workspace strip. */
        BELOW_COMPOSER
    }

    JComponent getComponent();

    /** The accessory's placement; defaults to {@link Placement#ABOVE_COMPOSER}. */
    default Placement getPlacement() {
        return Placement.ABOVE_COMPOSER;
    }

    /**
     * Optional: the host hands the accessory a sink for the chat composer's PLACEHOLDER text right
     * after mounting. The accessory may push updates at any time (on the EDT); {@code accept(null)}
     * restores the host's default placeholder. The host resets the placeholder itself when the
     * accessory is cleared/disposed, so implementations need no cleanup here.
     */
    default void bindPlaceholderSink(java.util.function.Consumer<String> sink) {
    }

    void dispose();
}
