package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.research.visualize.VisualizationProjection;
import com.aresstack.askai.research.visualize.VisualizationResult;
import com.aresstack.askai.research.visualize.VisualizationStatus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * The DERIVED "Visualisierung" view: renders the current {@link VisualizationProjection} with the HOST Mermaid
 * renderer. It is rebuildable, not a source-of-truth artifact — no editing, no approval. Issue #29: opening
 * this tab NEVER generates anything; the persisted result (possibly marked STALE) is displayed and the
 * explicit "Visualisierung erzeugen" / "Neu erzeugen" button is the ONLY generation trigger. The message shown
 * for a non-diagram state depends on the {@link VisualizationStatus}, so "never ran", "in Arbeit", "nichts
 * Sinnvolles zu zeigen" and "fehlgeschlagen" are visibly distinct rather than one generic placeholder.
 * All methods run on the EDT.
 */
public final class ResearchVisualizationView extends JPanel {

    private static final String NOT_STARTED =
            "_Noch keine Visualisierung. Erzeuge sie mit „Visualisierung erzeugen“._";
    private static final String PREPARING = "_Visualisierung wird vorbereitet …_";
    private static final String RUNNING = "_Visualisierung wird erzeugt …_";
    private static final String NONE_DECIDED =
            "_Zurzeit gibt es keine sinnvolle Visualisierung für diese Fragestellung._";
    private static final String FAILED = "_Die Visualisierung konnte nicht erzeugt werden._";
    private static final String BROKEN = "_Die Visualisierung konnte nicht dargestellt werden._";
    private static final String STALE_NOTE = "Veraltet — die Fragestellung hat sich geändert.";

    private final JLabel title = new JLabel();
    private final JLabel staleNote = new JLabel();
    private final JButton generateButton = new JButton("Visualisierung erzeugen");
    private final MarkdownView markdownView;

    public ResearchVisualizationView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.markdownView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(true).selectable(true).build());
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        staleNote.setVisible(false);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(generateButton);
        toolbar.add(staleNote);
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar, BorderLayout.NORTH);
        north.add(title, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(markdownView.getComponent(), BorderLayout.CENTER);
    }

    /** The explicit generation trigger (issue #29) — the ONLY thing that starts the visualization model. */
    public void setGenerateAction(Runnable action) {
        for (java.awt.event.ActionListener listener : generateButton.getActionListeners()) {
            generateButton.removeActionListener(listener);
        }
        final Runnable r = action;
        generateButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (r != null) {
                    r.run();
                }
            }
        });
    }

    /**
     * Show the persisted/derived visualization (or a status-specific placeholder), the stale marker and the
     * matching button label. NEVER triggers generation itself.
     */
    public void render(VisualizationStatus status, VisualizationProjection projection, boolean stale) {
        boolean generating = status == VisualizationStatus.PREPARING
                || status == VisualizationStatus.RUNNING;
        VisualizationResult result = projection == null ? null : projection.getResult();
        boolean hasAnyResult = projection != null;
        generateButton.setText(hasAnyResult ? "Neu erzeugen" : "Visualisierung erzeugen");
        generateButton.setEnabled(!generating);
        staleNote.setText(stale ? STALE_NOTE : "");
        staleNote.setVisible(stale);
        if (result != null && result.isPresent() && !generating) {
            title.setText(result.getTitle());
            try {
                markdownView.setMarkdown("```mermaid\n" + result.getMermaid() + "\n```");
            } catch (RuntimeException renderingFailed) {
                markdownView.setMarkdown(BROKEN);
            }
            return;
        }
        title.setText("");
        markdownView.setMarkdown(placeholderFor(status));
    }

    private static String placeholderFor(VisualizationStatus status) {
        if (status == null) {
            return NOT_STARTED;
        }
        switch (status) {
            case PREPARING:
                return PREPARING;
            case RUNNING:
                return RUNNING;
            case NONE_DECIDED:
                return NONE_DECIDED;
            case FAILED:
                return FAILED;
            case HAS_DIAGRAM:
                // status says diagram but none was passed (e.g. stale projection) — treat as not-yet.
            case NOT_STARTED:
            default:
                return NOT_STARTED;
        }
    }

    public void dispose() {
        markdownView.dispose();
    }
}
