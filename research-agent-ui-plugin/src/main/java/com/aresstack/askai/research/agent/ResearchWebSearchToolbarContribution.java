package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicSearchTag;
import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The "Websuche" tag in the workspace top bar: the {@link ComicSearchTag} (yellow chip look, ink
 * magnifier), TRAILING at the far right of the top bar (the old centered spot now belongs to the
 * phase selector). Firing runs {@link ResearchAgentSession#requestManualWebSearch}
 * — the SAME phase-independent /search path as a suggestion click, so the captured sources flow
 * into the corpus and the bot can review them afterwards. Never a chat turn, never a state command.
 * (The mindmap button moved into the CENTERED overlay-button strip, see
 * {@link ResearchOverlayButtonsToolbarContribution}.)
 */
public final class ResearchWebSearchToolbarContribution implements AgentToolbarContribution {

    @Override
    public String getId() {
        return "research-web-search";
    }

    @Override
    public Placement getPlacement() {
        return Placement.TRAILING;
    }

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public JComponent createComponent(final AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        final ComicSearchTag tag = new ComicSearchTag(
                "Websuche…", "Direkt im Web suchen (wie /search)");
        tag.addSearchAction(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                String query = tag.getText().trim();
                if (query.isEmpty()) {
                    return;
                }
                tag.setText(""); // the query lives on as the visible "Websuche:" breadcrumb
                session.requestManualWebSearch(query);
            }
        });

        return tag; // the mindmap button lives in the centered overlay strip now
    }

    /**
     * A tiny painted mindmap glyph (center node + three branches) — deterministic on every JRE.
     * Two variants share the geometry: the default (CONCEPT) look keeps the yellow core with dot
     * leaves; the SOURCES look says "these leaves are web content" — red core, and each leaf is a
     * small {@code <>} tag pair instead of a dot (sources are links, not ideas).
     */
    static final class MindmapIcon implements Icon {

        private static final int SIZE = 16;

        private final boolean sourceTags;

        MindmapIcon() {
            this(false);
        }

        MindmapIcon(boolean sourceTags) {
            this.sourceTags = sourceTags;
        }

        public int getIconWidth() {
            return SIZE;
        }

        public int getIconHeight() {
            return SIZE;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                ComicPalette palette = ComicPalette.defaultPalette();
                int cx = x + 5;
                int cy = y + 8;
                g2.setColor(palette.getInk());
                g2.setStroke(new BasicStroke(1.4f));
                if (sourceTags) {
                    // Branches end at the opening bracket's tip so the tags stay readable.
                    g2.drawLine(cx, cy, x + 9, y + 3);
                    g2.drawLine(cx, cy, x + 10, y + 8);
                    g2.drawLine(cx, cy, x + 9, y + 13);
                } else {
                    g2.drawLine(cx, cy, x + 12, y + 3);
                    g2.drawLine(cx, cy, x + 13, y + 8);
                    g2.drawLine(cx, cy, x + 12, y + 13);
                }
                g2.setColor(sourceTags ? palette.getAccentRed() : palette.getAccentYellow());
                g2.fillOval(cx - 3, cy - 3, 6, 6);
                g2.setColor(palette.getInk());
                g2.drawOval(cx - 3, cy - 3, 6, 6);
                if (sourceTags) {
                    g2.setStroke(new BasicStroke(1.2f));
                    paintTagPair(g2, x + 12, y + 3);
                    paintTagPair(g2, x + 13, y + 8);
                    paintTagPair(g2, x + 12, y + 13);
                } else {
                    g2.fillOval(x + 10, y + 1, 4, 4);
                    g2.fillOval(x + 11, y + 6, 4, 4);
                    g2.fillOval(x + 10, y + 11, 4, 4);
                }
            } finally {
                g2.dispose();
            }
        }

        /** One {@code <>} leaf centered at (cx, cy) — the web-tag look of a source endpoint. */
        private static void paintTagPair(Graphics2D g2, int cx, int cy) {
            g2.drawLine(cx - 1, cy - 2, cx - 3, cy);
            g2.drawLine(cx - 3, cy, cx - 1, cy + 2);
            g2.drawLine(cx + 1, cy - 2, cx + 3, cy);
            g2.drawLine(cx + 3, cy, cx + 1, cy + 2);
        }
    }
}
