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

    /** Show a closable overlay over the ACTIVE chat's transcript (e.g. a hint panel). */
    void showTranscriptOverlay(JComponent content, String title);

    /** Show a Mermaid SOURCE in the host's full diagram viewer as a transcript overlay. */
    void showDiagramOverlay(String mermaidSource, String title);
}
