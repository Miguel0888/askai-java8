package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.control.ResearchPillButton;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Phase-1 workspace strip of the design study: {@code ☠ Ausschlüsse (Blacklist)} + one chip per
 * plain exclusion + the "+ Hinzufügen" control, on the dark {@link
 * ResearchUiPalette#WORKSPACE_SURFACE}. Pure view: it renders what {@link #setExclusions} hands it
 * and reports add/remove intents through the injected actions — the session owns the scope draft.
 * Chips keep their 43px height always; when they overflow, the row scrolls horizontally (mouse
 * wheel), never wraps and never squeezes.
 */
final class ResearchBlacklistPanel extends JPanel {

    /** The skull as TEXT where the font can render it cleanly; the painted icon otherwise. */
    private static final char SKULL = '☠';

    private final JPanel chipRow = new JPanel();
    private final JScrollPane chipScroll;
    private final ResearchPillButton addButton;
    private Consumer<String> addAction;
    private Consumer<String> removeAction;

    ResearchBlacklistPanel() {
        super(new BorderLayout(ResearchUiMetrics.BLACKLIST_LABEL_GAP, 0));
        setOpaque(true);
        setBackground(ResearchUiPalette.WORKSPACE_SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ResearchUiPalette.WORKSPACE_DIVIDER),
                BorderFactory.createEmptyBorder(
                        ResearchUiMetrics.BLACKLIST_PADDING_TOP,
                        ResearchUiMetrics.BLACKLIST_PADDING_LEFT,
                        ResearchUiMetrics.BLACKLIST_PADDING_BOTTOM,
                        ResearchUiMetrics.BLACKLIST_PADDING_RIGHT)));

        add(buildLabelZone(), BorderLayout.WEST);

        chipRow.setLayout(new BoxLayout(chipRow, BoxLayout.X_AXIS));
        chipRow.setOpaque(false);
        // A borderless viewport without visible scrollbars: the mouse wheel scrolls the row.
        chipScroll = new JScrollPane(chipRow,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chipScroll.setBorder(BorderFactory.createEmptyBorder());
        chipScroll.setOpaque(false);
        chipScroll.getViewport().setOpaque(false);
        chipScroll.addMouseWheelListener(event -> {
            javax.swing.JScrollBar bar = chipScroll.getHorizontalScrollBar();
            bar.setValue(bar.getValue() + event.getWheelRotation() * 32);
        });
        add(chipScroll, BorderLayout.CENTER);

        addButton = new ResearchPillButton("＋ Hinzufügen",
                ResearchUiMetrics.ADD_CHIP_HEIGHT, ResearchUiMetrics.RADIUS_SECONDARY,
                ResearchUiMetrics.ADD_CHIP_PADDING_H);
        addButton.setFont(ResearchUiTypography.regular(12f));
        addButton.setToolTipText("Ausschluss hinzufügen");
        addButton.addActionListener(event -> openAddPopup());
    }

    void setAddAction(Consumer<String> action) {
        this.addAction = action;
    }

    void setRemoveAction(Consumer<String> action) {
        this.removeAction = action;
    }

    /** Re-render the chip row from the draft's plain exclusions. EDT only. */
    void setExclusions(List<String> exclusions) {
        chipRow.removeAll();
        for (String exclusion : exclusions) {
            chipRow.add(new ExclusionChip(exclusion));
            chipRow.add(Box.createHorizontalStrut(ResearchUiMetrics.CHIP_GAP));
        }
        chipRow.add(addButton);
        chipRow.add(Box.createHorizontalGlue());
        chipRow.revalidate();
        chipRow.repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(size.width, ResearchUiMetrics.BLACKLIST_HEIGHT);
    }

    // ------------------------------------------------------------------ label zone

    private JComponent buildLabelZone() {
        boolean skullAsText = ResearchUiTypography.semiBold(13f).canDisplay(SKULL);
        JLabel title = new JLabel(skullAsText ? SKULL + " Ausschlüsse" : "Ausschlüsse");
        if (!skullAsText) {
            title.setIcon(new SkullIcon());
            title.setIconTextGap(6);
        }
        title.setFont(ResearchUiTypography.semiBold(13f));
        title.setForeground(ResearchUiPalette.TEXT_PRIMARY);
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("(Blacklist)");
        subtitle.setFont(ResearchUiTypography.regular(11.5f));
        subtitle.setForeground(ResearchUiPalette.TEXT_MUTED);
        subtitle.setBorder(BorderFactory.createEmptyBorder(0, skullAsText ? 16 : 0, 0, 0));
        subtitle.setAlignmentX(LEFT_ALIGNMENT);

        JPanel zone = new JPanel();
        zone.setLayout(new BoxLayout(zone, BoxLayout.Y_AXIS));
        zone.setOpaque(false);
        zone.add(Box.createVerticalGlue());
        zone.add(title);
        zone.add(subtitle);
        zone.add(Box.createVerticalGlue());
        Dimension width = new Dimension(ResearchUiMetrics.BLACKLIST_LABEL_WIDTH, 10);
        zone.setPreferredSize(new Dimension(width.width, zone.getPreferredSize().height));
        zone.setMaximumSize(new Dimension(width.width, Integer.MAX_VALUE));
        return zone;
    }

    // ------------------------------------------------------------------ add popup

    /** A small dark popup with one text field: Enter records the exclusion, Escape closes. */
    private void openAddPopup() {
        final JPopupMenu popup = new JPopupMenu();
        popup.setBackground(ResearchUiPalette.DARK_SURFACE);
        popup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPalette.BORDER_WINDOW),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        final JTextField field = new JTextField(22);
        field.setFont(ResearchUiTypography.regular(12f));
        field.setBackground(ResearchUiPalette.CHIP_SURFACE);
        field.setForeground(ResearchUiPalette.TEXT_PRIMARY);
        field.setCaretColor(ResearchUiPalette.TEXT_PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPalette.BORDER_DARK),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        field.addActionListener(event -> {
            String value = field.getText().trim();
            popup.setVisible(false);
            if (!value.isEmpty() && addAction != null) {
                addAction.accept(value);
            }
        });
        popup.add(field);
        popup.show(addButton, 0, -popup.getPreferredSize().height - 4);
        field.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ chip

    /** One {@code ☠ text ×} pill; the ✕ is its own ≥20×20 hit area with its own hover. */
    private final class ExclusionChip extends JComponent {

        private final String text;
        private final boolean skullAsText;
        private boolean hovered;
        private boolean closeHovered;

        ExclusionChip(String text) {
            this.text = text;
            this.skullAsText = ResearchUiTypography.regular(12f).canDisplay(SKULL);
            setToolTipText(text);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    closeHovered = false;
                    repaint();
                }

                @Override
                public void mouseMoved(java.awt.event.MouseEvent event) {
                    boolean inClose = closeHit().contains(event.getPoint());
                    if (inClose != closeHovered) {
                        closeHovered = inClose;
                        setCursor(Cursor.getPredefinedCursor(
                                inClose ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        repaint();
                    }
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    if (closeHit().contains(event.getPoint()) && removeAction != null) {
                        removeAction.accept(ExclusionChip.this.text);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private java.awt.Rectangle closeHit() {
            int hit = ResearchUiMetrics.CHIP_CLOSE_HIT;
            int x = getWidth() - ResearchUiMetrics.CHIP_PADDING_H - hit + 4;
            return new java.awt.Rectangle(x, (getHeight() - hit) / 2, hit, hit);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                int radius = ResearchUiMetrics.RADIUS_CHIP;
                ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius,
                        hovered ? ResearchUiPalette.CHIP_HOVER_SURFACE
                                : ResearchUiPalette.CHIP_SURFACE);
                ResearchUiPainter.strokeRound(g2, 0, 0, getWidth(), getHeight(), radius,
                        hovered ? ResearchUiPalette.CHIP_HOVER_BORDER
                                : ResearchUiPalette.BORDER_DARK);

                Font font = ResearchUiTypography.regular(12f);
                g2.setFont(font);
                FontMetrics metrics = g2.getFontMetrics();
                int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                int x = ResearchUiMetrics.CHIP_PADDING_H;
                g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
                if (skullAsText) {
                    g2.drawString(String.valueOf(SKULL), x, textY);
                    x += metrics.charWidth(SKULL) + ResearchUiMetrics.CHIP_SKULL_TEXT_GAP;
                } else {
                    new SkullIcon().paintIcon(this, g2, x, (getHeight() - 12) / 2);
                    x += 12 + ResearchUiMetrics.CHIP_SKULL_TEXT_GAP;
                }
                g2.drawString(text, x, textY);

                // The ✕ — its own hit area, optionally on a round hover backdrop.
                java.awt.Rectangle close = closeHit();
                if (closeHovered) {
                    g2.setColor(ResearchUiPalette.CHIP_CLOSE_HOVER);
                    g2.fillOval(close.x, close.y, close.width, close.height);
                }
                g2.setColor(closeHovered ? java.awt.Color.WHITE : ResearchUiPalette.TEXT_MUTED);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = close.x + close.width / 2;
                int cy = close.y + close.height / 2;
                g2.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                g2.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.regular(12f));
            int skullWidth = skullAsText ? metrics.charWidth(SKULL) : 12;
            int width = ResearchUiMetrics.CHIP_PADDING_H
                    + skullWidth + ResearchUiMetrics.CHIP_SKULL_TEXT_GAP
                    + metrics.stringWidth(text) + ResearchUiMetrics.CHIP_TEXT_CLOSE_GAP
                    + ResearchUiMetrics.CHIP_CLOSE_HIT - 4
                    + ResearchUiMetrics.CHIP_PADDING_H;
            return new Dimension(width, ResearchUiMetrics.CHIP_HEIGHT);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize(); // never squeezed — the row scrolls instead
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }

    /** A 12×12 monochrome skull for fonts without a clean {@code ☠} glyph. */
    private static final class SkullIcon implements javax.swing.Icon {

        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 12;
        }

        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
                g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(x + 1, y, 10, 8);       // cranium
                g2.drawLine(x + 4, y + 8, x + 4, y + 11);  // jaw hints
                g2.drawLine(x + 8, y + 8, x + 8, y + 11);
                g2.fillOval(x + 3, y + 3, 2, 2);    // eyes
                g2.fillOval(x + 7, y + 3, 2, 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
