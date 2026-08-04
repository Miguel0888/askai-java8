package com.aresstack.askai.plugin.host;

import javax.swing.JComponent;

/** Where the workspace shows the active agent's top-bar controls (left of the gear). */
public interface AgentToolbarHost {

    void setToolbar(JComponent component);

    void clearToolbar();
}
