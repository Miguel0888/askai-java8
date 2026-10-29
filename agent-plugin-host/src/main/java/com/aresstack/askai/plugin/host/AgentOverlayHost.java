package com.aresstack.askai.plugin.host;

/**
 * The host's transcript-overlay capability, handed to the shared artifact views so a view can open the same
 * overlays the toolbar contributions use (e.g. a generated diagram from a "Visualize" button in its footer).
 */
public interface AgentOverlayHost {

    /** Show content as a closable overlay over the active chat's transcript. */
    void showTranscriptOverlay(javax.swing.JComponent content, String title);

    /** Show a Mermaid diagram (source) in the host's diagram viewer overlay. */
    void showDiagramOverlay(String mermaidSource, String title);
}
