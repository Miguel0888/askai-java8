package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.List;

/**
 * #43 slice 8 — the GRAPHICAL Outline: numbered chapter plates in the concept tree's comic
 * idiom (ink elbows, rounded plates, hand-drawn feel) so Concept and Outline read as visual
 * siblings while staying different artifacts. Chapter numbers are part of the visual identity
 * ({@code 1.}, {@code 1.1} …); annotations (open questions) render muted beneath their
 * chapter. READ-ONLY on purpose — editing/reordering is #40's slice; no live-outline
 * semantics return here.
 */
final class OutlineTreeView extends JComponent implements Scrollable {

    private static final int ROW_HEIGHT = 34;
    private static final int INDENT = 26;
    private static final int MARGIN = 14;
    private static final int PLATE_ARC = 12;
    private static final int PLATE_PAD_H = 12;
    private static final int PLATE_HEIGHT = 26;

    private List<OutlineTreeModel.Row> rows = Collections.emptyList();

    OutlineTreeView() {
        setOpaque(false);
    }

    void setRows(List<OutlineTreeModel.Row> rows) {
        this.rows = rows == null ? Collections.<OutlineTreeModel.Row>emptyList() : rows;
        revalidate();
        repaint();
    }

    boolean hasRows() {
        return !rows.isEmpty();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(ResearchUiTypography.semiBold(13f));
        int widest = 0;
        for (OutlineTreeModel.Row row : rows) {
            int width = MARGIN + row.depth * INDENT
                    + metrics.stringWidth(label(row)) + 2 * PLATE_PAD_H + MARGIN;
            widest = Math.max(widest, width);
        }
        return new Dimension(Math.max(widest, 240), MARGIN * 2 + rows.size() * ROW_HEIGHT);
    }

    private static String label(OutlineTreeModel.Row row) {
        return row.annotation ? row.title : row.number + "  " + row.title;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color ink = ResearchUiPalette.TEXT_PRIMARY;
            Color muted = new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 150);
            for (int index = 0; index < rows.size(); index++) {
                OutlineTreeModel.Row row = rows.get(index);
                int x = MARGIN + row.depth * INDENT;
                int y = MARGIN + index * ROW_HEIGHT;
                int mid = y + PLATE_HEIGHT / 2;
                if (row.depth > 0) {
                    // The ink elbow up to the nearest shallower row — the concept tree's idiom.
                    int parentX = MARGIN + (row.depth - 1) * INDENT + PLATE_PAD_H;
                    int parentBottom = elbowTop(index, row.depth) ;
                    g2.setColor(muted);
                    g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g2.drawLine(parentX, parentBottom, parentX, mid);
                    g2.drawLine(parentX, mid, x - 4, mid);
                }
                if (row.annotation) {
                    g2.setColor(muted);
                    g2.setFont(ResearchUiTypography.regular(12.5f).deriveFont(
                            java.awt.Font.ITALIC));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(row.title, x + PLATE_PAD_H,
                            mid + (fm.getAscent() - fm.getDescent()) / 2);
                    continue;
                }
                FontMetrics fm = getFontMetrics(ResearchUiTypography.semiBold(13f));
                int plateWidth = Math.max(48, fm.stringWidth(label(row)) + 2 * PLATE_PAD_H);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(x, y, plateWidth, PLATE_HEIGHT, PLATE_ARC, PLATE_ARC);
                g2.setColor(ink);
                g2.setStroke(new BasicStroke(row.depth == 0 ? 2f : 1.5f));
                g2.drawRoundRect(x, y, plateWidth, PLATE_HEIGHT, PLATE_ARC, PLATE_ARC);
                // The chapter number is part of the plate's identity: accent first, title after.
                g2.setFont(ResearchUiTypography.semiBold(13f));
                FontMetrics pm = g2.getFontMetrics();
                int baseline = y + (PLATE_HEIGHT + pm.getAscent() - pm.getDescent()) / 2;
                g2.setColor(new Color(0xB0, 0x52, 0x18));
                g2.drawString(row.number, x + PLATE_PAD_H, baseline);
                g2.setColor(ink);
                g2.drawString(row.title,
                        x + PLATE_PAD_H + pm.stringWidth(row.number + "  "), baseline);
            }
        } finally {
            g2.dispose();
        }
    }

    /** The y where this row's elbow starts: the bottom of the nearest shallower row above. */
    private int elbowTop(int index, int depth) {
        for (int above = index - 1; above >= 0; above--) {
            if (rows.get(above).depth < depth) {
                return MARGIN + above * ROW_HEIGHT + PLATE_HEIGHT;
            }
        }
        return MARGIN;
    }

    // ------------------------------------------------------------------ Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return ROW_HEIGHT;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == javax.swing.SwingConstants.VERTICAL
                ? visible.height : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
