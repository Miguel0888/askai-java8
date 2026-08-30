package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.concept.ConceptBranchService;
import com.aresstack.askai.research.concept.ConceptProjection;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * The "Konzept visualisieren" entry point: render the CURRENT concept snapshot as the same
 * Mermaid mindmap the Konzept tab shows, in the host's diagram overlay (zoom/pan/high-res —
 * the plugin never renders Mermaid itself). Purely mechanical, same pipeline as the agent
 * tools; an empty or unavailable concept gets an honest hint, never a broken diagram.
 */
final class ConceptMindmapAction {

    static final String OVERLAY_TITLE = "Concept Mindmap";

    /** Same overlay contract as the sources mindmap. */
    interface OverlayHost {
        void showDiagram(String mermaidSource, String title);

        void showHint(JComponent content, String title);
    }

    private ConceptMindmapAction() {
    }

    static void open(ResearchAgentSession session, OverlayHost host) {
        ConceptBranchService service = session.conceptBranchService();
        if (service == null) {
            host.showHint(hint("This session has no concept service."), OVERLAY_TITLE);
            return;
        }
        ConceptProjection projection = ConceptProjection.of(service.snapshot());
        if (!projection.isReadable()) {
            host.showHint(hint("Concept not readable: " + projection.getDiagnosticText()),
                    OVERLAY_TITLE);
            return;
        }
        if (projection.isEmptyConcept()) {
            host.showHint(hint("No concept yet — describe in the chat what you want to research."), OVERLAY_TITLE);
            return;
        }
        host.showDiagram(projection.getMermaid(), OVERLAY_TITLE);
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        return label;
    }
}
