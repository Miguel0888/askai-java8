package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.research.concept.ConceptProjection;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;

/**
 * The Konzept tab's content: PURE additional views of the ONE store state — no second UI model,
 * no copy of the concept in Swing state. [JSON] renders the atomic snapshot (the revision label
 * proves which one); [Brief] keeps the legacy research-brief markdown visible until K4 retires
 * it (with the toggle row; a concept search bar takes the freed spot then). The MINDMAP is
 * deliberately NOT a card here: it lives behind the "Visualize concept" toolbar button, in the
 * SAME host diagram overlay as the sources mindmap — the tab shows the raw truth. The JSON view
 * is READ-ONLY by design: editing arrives only when it can go through the same
 * parse→candidate→validate→commit path as everyone else.
 *
 * <p>Comic dress: the content gets the full height; every control sits in ONE footer strip in
 * the chats-footer idiom — view toggles left, revision + manual reload right. All methods EDT.</p>
 */
public final class ConceptPaperView extends JPanel {

    private static final String EMPTY =
            "_No concept yet. Describe in the chat what you want to research._";

    private final MarkdownView jsonView;
    private final ResearchBriefView briefView;
    private final JLabel revisionLabel = new JLabel(" ");
    private final ComicButton refreshButton = new ComicButton("⟳");
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
        setOpaque(false);
        this.jsonView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(false).selectable(true).build());
        this.briefView = new ResearchBriefView(markdownViewFactory);

        cardPanel.setLayout(cards);
        cardPanel.setOpaque(false);
        cardPanel.add(jsonView.getComponent(), "json");
        cardPanel.add(briefView, "brief");

        add(cardPanel, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    /**
     * The ONE control strip, pinned to the bottom like the chats footer: [JSON][Brief] toggles
     * left, the quiet revision witness and the manual reload right. Manual ⟳ for ALL cases — the
     * auto-listeners cover the normal paths, but a human must never depend on them to see the
     * current workpiece.
     */
    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));

        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toggles.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        toggles.add(viewToggle(group, "JSON", "json", true));
        toggles.add(viewToggle(group, "Brief", "brief", false));
        footer.add(toggles, BorderLayout.WEST);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        status.setOpaque(false);
        revisionLabel.setFont(ResearchUiTypography.regular(11.5f));
        revisionLabel.setEnabled(false); // quiet gray, diagnostic value only
        status.add(revisionLabel);
        refreshButton.setFocusable(false);
        refreshButton.setToolTipText("Reload view");
        refreshButton.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        status.add(refreshButton);
        footer.add(status, BorderLayout.EAST);
        return footer;
    }

    private JToggleButton viewToggle(ButtonGroup group, String label, final String card,
                                     boolean selected) {
        JToggleButton button = new ComicToggle(label, selected);
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

    /**
     * The comic sibling of {@link ComicButton} for an exclusive VIEW choice: same plate/ink
     * language, but the yellow accent marks the SELECTED card (a state, not a hover moment).
     * Deliberately private — the toggle pair retires with K4 together with the [Brief] card.
     */
    private static final class ComicToggle extends JToggleButton {

        private static final int ARC = 10;
        private final ComicPalette palette = ComicPalette.defaultPalette();

        ComicToggle(String label, boolean selected) {
            super(label, selected);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setRolloverEnabled(true);
            setForeground(palette.getInk());
            setFont(ResearchUiTypography.semiBold(12f));
            setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D plate = new RoundRectangle2D.Float(
                        1f, 1f, getWidth() - 2f, getHeight() - 2f, ARC, ARC);
                g2.setColor(plateFill());
                g2.fill(plate);
                g2.setColor(palette.getInk());
                g2.setStroke(new java.awt.BasicStroke(1.6f));
                g2.draw(plate);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        private Color plateFill() {
            ButtonModel model = getModel();
            if (model.isSelected()) {
                return palette.getAccentYellow();
            }
            return model.isRollover() ? palette.getSurface() : Color.WHITE;
        }
    }
}
