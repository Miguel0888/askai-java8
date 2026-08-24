package com.aresstack.askai.plugin.host;

import javax.swing.JComponent;

/**
 * Where the workspace shows the active agent's top-bar controls: the TRAILING group (left of the
 * gear) and the CENTERED slot between ribbon and trailing controls.
 */
public interface AgentToolbarHost {

    void setToolbar(JComponent component);

    void clearToolbar();

    /** Show a centered top-bar control (e.g. a session search field). */
    void setCenterToolbar(JComponent component);

    void clearCenterToolbar();

    /** Show a closable overlay over the ACTIVE chat's transcript (e.g. a generated diagram). */
    void showTranscriptOverlay(JComponent content, String title);
}
