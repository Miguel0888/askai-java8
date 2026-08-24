package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;
import java.util.function.BiConsumer;

/**
 * The ONE mindmap entry point shared by the toolbar button and {@code /map}: build the mechanical
 * sources mindmap from the session and hand the overlay content (diagram, or the honest
 * nothing-qualifies hint) to the host's transcript overlay.
 */
final class ResearchMindmapAction {

    static final String OVERLAY_TITLE = "Quellen-Mindmap";

    private ResearchMindmapAction() {
    }

    static void open(ResearchAgentSession session, UiExecutor uiExecutor,
                     BiConsumer<JComponent, String> overlaySink) {
        String mermaid = session.buildSourceMindmapMermaid();
        JComponent content = mermaid == null
                ? MindmapOverlayView.empty() : MindmapOverlayView.render(mermaid, uiExecutor);
        overlaySink.accept(content, OVERLAY_TITLE);
    }
}
