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

    void dispose();
}
