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
 * no copy of the concept in Swing state. [Mindmap] and [JSON] render the SAME atomic snapshot
 * (the revision label proves it); [Brief] keeps the legacy research-brief markdown visible until
 * K4 retires it. The JSON view is READ-ONLY by design: editing arrives only when it can go
 * through the same parse→candidate→validate→commit path as everyone else. All methods EDT.
 */
public final class ConceptPaperView extends JPanel {

    private static final String EMPTY =
            "_Noch kein Konzept. Beschreibe im Chat, was du erforschen möchtest._";

    private final MarkdownView mindmapView;
    private final MarkdownView jsonView;
    private final ResearchBriefView briefView;
    private final JLabel revisionLabel = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel();

    public ConceptPaperView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.mindmapView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(true).selectable(true).build());
        this.jsonView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(false).selectable(true).build());
        this.briefView = new ResearchBriefView(markdownViewFactory);

        cardPanel.setLayout(cards);
        cardPanel.add(mindmapView.getComponent(), "mindmap");
        cardPanel.add(jsonView.getComponent(), "json");
        cardPanel.add(briefView, "brief");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        bar.add(viewToggle(group, "Mindmap", "mindmap", true));
        bar.add(viewToggle(group, "JSON", "json", false));
        bar.add(viewToggle(group, "Brief", "brief", false));
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
     * Render one atomic snapshot into ALL cards at once — mindmap and JSON can never drift
     * apart. {@code projection} may be {@code null} (clickdummy without a concept service).
     */
    public void render(ConceptProjection projection, String briefMarkdown) {
        briefView.render(briefMarkdown);
        if (projection == null) {
            mindmapView.setMarkdown(EMPTY);
            jsonView.setMarkdown(EMPTY);
            revisionLabel.setText(" ");
            return;
        }
        revisionLabel.setText("rev " + projection.getWorkingRevision());
        if (!projection.isReadable()) {
            // Never a broken diagram, never creative repair: the honest diagnosis instead.
            mindmapView.setMarkdown("**Konzept nicht lesbar**\n\n```\n"
                    + projection.getDiagnosticText() + "\n```");
            jsonView.setMarkdown("```\n" + projection.getPrettyJson() + "\n```");
            return;
        }
        mindmapView.setMarkdown(projection.isEmptyConcept()
                ? EMPTY
                : "```mermaid\n" + projection.getMermaid() + "```");
        jsonView.setMarkdown("```json\n" + projection.getPrettyJson() + "\n```");
    }

    public void dispose() {
        mindmapView.dispose();
        jsonView.dispose();
        briefView.dispose();
    }
}
