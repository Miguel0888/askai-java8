package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.research.concept.ConceptProjection;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The Konzept tab's content: PURE additional views of the ONE store state — no second UI model,
 * no copy of the concept in Swing state. [JSON] renders the atomic snapshot (the revision label
 * proves which one); [Brief] keeps the legacy research-brief markdown visible until K4 retires
 * it. The MINDMAP is deliberately NOT a card here: it lives behind the "Konzept visualisieren"
 * toolbar button, in the SAME host diagram overlay as the sources mindmap (the proper rendering
 * engine with zoom/pan) — the tab shows the raw truth. The JSON view is READ-ONLY by design:
 * editing arrives only when it can go through the same parse→candidate→validate→commit path as
 * everyone else. All methods EDT.
 */
public final class ConceptPaperView extends JPanel {

    private static final String EMPTY =
            "_No concept yet. Describe in the chat what you want to research._";

    private final MarkdownView jsonView;
    private final ResearchBriefView briefView;
    private final JLabel revisionLabel = new JLabel(" ");
    private final javax.swing.JButton refreshButton = new javax.swing.JButton("⟳");
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel();

    /** Wire the manual ⟳ button to the owner's re-read (the same runnable the listeners use). */
    public void setRefreshAction(final Runnable refresh) {
        for (ActionListener old : refreshButton.getActionListeners()) {
            refreshButton.removeActionListener(old);
        }
        refreshButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                refresh.run();
            }
        });
    }

    public ConceptPaperView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.jsonView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(false).selectable(true).build());
        this.briefView = new ResearchBriefView(markdownViewFactory);

        cardPanel.setLayout(cards);
        cardPanel.add(jsonView.getComponent(), "json");
        cardPanel.add(briefView, "brief");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        bar.add(viewToggle(group, "JSON", "json", true));
        bar.add(viewToggle(group, "Brief", "brief", false));
        // Manual refresh for ALL cases — the auto-listeners cover the normal paths, but a
        // human must never depend on them to see the current workpiece.
        refreshButton.setFocusable(false);
        refreshButton.setToolTipText("Reload view");
        bar.add(refreshButton);
        revisionLabel.setEnabled(false); // quiet gray, diagnostic value only
        bar.add(revisionLabel);

        add(bar, BorderLayout.NORTH);
        add(cardPanel, BorderLayout.CENTER);
    }

    private JToggleButton viewToggle(ButtonGroup group, String label, final String card,
                                     boolean selected) {
        JToggleButton button = new JToggleButton(label, selected);
        button.setFocusable(false);
        group.add(button);
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                cards.show(cardPanel, card);
            }
        });
        return button;
    }

    /**
     * Render one atomic snapshot into ALL cards at once. {@code projection} may be {@code null}
     * (clickdummy without a concept service).
     */
    public void render(ConceptProjection projection, String briefMarkdown) {
        briefView.render(briefMarkdown);
        if (projection == null) {
            jsonView.setMarkdown(EMPTY);
            revisionLabel.setText(" ");
            return;
        }
        revisionLabel.setText("rev " + projection.getWorkingRevision());
        if (!projection.isReadable()) {
            // Never creative repair: the honest diagnosis leads, the raw text stays visible.
            jsonView.setMarkdown("**Concept not readable**\n\n```\n"
                    + projection.getDiagnosticText() + "\n```\n\n```\n"
                    + projection.getPrettyJson() + "\n```");
            return;
        }
        jsonView.setMarkdown("```json\n" + projection.getPrettyJson() + "\n```");
    }

    public void dispose() {
        jsonView.dispose();
        briefView.dispose();
    }
}
