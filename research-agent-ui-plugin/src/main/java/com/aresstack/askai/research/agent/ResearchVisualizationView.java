package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.research.visualize.VisualizationProjection;
import com.aresstack.askai.research.visualize.VisualizationResult;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * The DERIVED "Visualisierung" view: renders the current {@link VisualizationProjection} with the HOST Mermaid
 * renderer. It is rebuildable, not a source-of-truth artifact — no editing, no approval. NONE renders a neutral
 * placeholder; a broken diagram degrades to a visualizer-only message (the brief and workflow are untouched).
 * All methods run on the EDT.
 */
public final class ResearchVisualizationView extends JPanel {

    private static final String EMPTY =
            "_Noch keine Visualisierung. Sie wird automatisch erzeugt, sobald die Fragestellung genug "
                    + "Struktur enthält._";
    private static final String BROKEN = "_Die Visualisierung konnte nicht dargestellt werden._";

    private final JLabel title = new JLabel();
    private final MarkdownView markdownView;

    public ResearchVisualizationView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.markdownView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(true).selectable(true).build());
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);
        add(markdownView.getComponent(), BorderLayout.CENTER);
    }

    /** Show the current derived visualization (a placeholder for NONE / none-yet). */
    public void render(VisualizationProjection projection) {
        VisualizationResult result = projection == null ? null : projection.getResult();
        if (result == null || !result.isPresent()) {
            title.setText("");
            markdownView.setMarkdown(EMPTY);
            return;
        }
        title.setText(result.getTitle());
        try {
            markdownView.setMarkdown("```mermaid\n" + result.getMermaid() + "\n```");
        } catch (RuntimeException renderingFailed) {
            markdownView.setMarkdown(BROKEN);
        }
    }

    public void dispose() {
        markdownView.dispose();
    }
}
