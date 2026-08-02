package com.aresstack.askai.plugin.api.agent.composer;

import javax.swing.JComponent;

/**
 * A persistent Swing component an agent plugin places directly ABOVE the chat composer (not in the artifact
 * sidepanel), visible while its agent session is the active one. The host owns the component's placement and
 * calls {@link #dispose()} exactly once when the session/agent/tab changes or the session closes — an explicit
 * lifecycle, so the component may be removed from and re-added to the layout without losing its listeners.
 * Every method runs on the EDT.
 */
public interface ComposerAccessory {

    JComponent getComponent();

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
