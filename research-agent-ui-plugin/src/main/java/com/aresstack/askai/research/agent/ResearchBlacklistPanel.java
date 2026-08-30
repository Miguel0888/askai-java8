package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.control.ResearchPillButton;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Phase-1 workspace strip, second pass: a COMPACT single row of exclusion chips that only ever
 * shows chips fitting COMPLETELY (the rest collapses into a quiet {@code +N weitere} summary chip —
 * nothing is ever clipped mid-glyph), plus a small muted {@code Ausschlüsse ⌃} toggle bottom-right.
 * Both the toggle and the summary chip open the SAME upward drawer (a popover anchored to this
 * strip, no dialog/modal): there the chips wrap over multiple rows, {@code + Hinzufügen} lives at
 * the end of the flow, and only the drawer scrolls once it exceeds {@link
 * ResearchUiMetrics#BLACKLIST_DRAWER_MAX_HEIGHT}. Pure view as before: it renders what {@link
 * #setExclusions} hands it and reports add/remove intents through the injected actions.
 */
final class ResearchBlacklistPanel extends JPanel {

    /** The skull as TEXT where the font can render it cleanly; the painted icon otherwise. */
    private static final char SKULL = '☠';

    private final CompactChipRow compactRow = new CompactChipRow();
    private final DrawerToggle toggle = new DrawerToggle();
    private Consumer<String> addAction;
    private Consumer<String> removeAction;
    private List<String> exclusions = Collections.emptyList();
    private JPopupMenu drawer;

    ResearchBlacklistPanel() {
        super(new BorderLayout(0, 4));
        setOpaque(true);
        setBackground(ResearchUiPalette.WORKSPACE_SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ResearchUiPalette.WORKSPACE_DIVIDER),
                BorderFactory.createEmptyBorder(
                        ResearchUiMetrics.BLACKLIST_PADDING_TOP,
                        ResearchUiMetrics.BLACKLIST_PADDING_LEFT,
                        ResearchUiMetrics.BLACKLIST_PADDING_BOTTOM,
                        ResearchUiMetrics.BLACKLIST_PADDING_RIGHT)));

        add(compactRow, BorderLayout.CENTER);
        JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        labelRow.setOpaque(false);
        labelRow.add(toggle);
        add(labelRow, BorderLayout.SOUTH);
    }

    void setAddAction(Consumer<String> action) {
        this.addAction = action;
    }

    void setRemoveAction(Consumer<String> action) {
        this.removeAction = action;
    }

    /** Re-render from the draft's plain exclusions; an open drawer follows along. EDT only. */
    void setExclusions(List<String> exclusions) {
        this.exclusions = new ArrayList<String>(exclusions);
        compactRow.rebuild();
        if (drawer != null && drawer.isVisible()) {
            // Chip count changed (add/remove): rebuild the anchored drawer at its new height.
            drawer.setVisible(false);
            openDrawer();
        }
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible && drawer != null) {
            drawer.setVisible(false); // a phase change must never leave an orphaned popover
        }
        super.setVisible(visible);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(size.width, ResearchUiMetrics.BLACKLIST_HEIGHT);
    }

    private static boolean skullAsText(Font font) {
        return font.canDisplay(SKULL);
    }

    // ------------------------------------------------------------------ the upward drawer

    private void toggleDrawer() {
        if (drawer != null && drawer.isVisible()) {
            drawer.setVisible(false);
            return;
        }
        openDrawer();
    }

    /** Build and show the popover ABOVE this strip: header, wrapping chips, + Hinzufügen. */
    private void openDrawer() {
        int width = Math.max(320, getWidth() - 2);
        // Conservative inner width (border + line strokes) so the simulated wrap NEVER underestimates rows.
        int innerWidth = width - 2 * 14 - 4;

        final JPanel chipsWrap = new JPanel(new JustifiedTagLayout(
                ResearchUiMetrics.CHIP_GAP, 8, false));
        chipsWrap.setOpaque(false);
        for (String exclusion : exclusions) {
            chipsWrap.add(new ExclusionChip(exclusion));
        }
        final ResearchPillButton addButton = new ResearchPillButton("＋ Hinzufügen",
                ResearchUiMetrics.ADD_CHIP_HEIGHT, ResearchUiMetrics.RADIUS_SECONDARY,
                ResearchUiMetrics.ADD_CHIP_PADDING_H);
        addButton.setFont(ResearchUiTypography.regular(12f));
        addButton.setToolTipText("Ausschluss hinzufügen");
        addButton.addActionListener(event -> beginInlineAdd(chipsWrap, addButton));
        chipsWrap.add(addButton);

        int wrapHeight = wrappedHeight(chipsWrap, innerWidth);
        boolean scrolls = wrapHeight > ResearchUiMetrics.BLACKLIST_DRAWER_MAX_HEIGHT;
        JComponent chipsArea;
        if (scrolls) {
            // Only the expanded area scrolls — the strip below never does.
            JScrollPane scroll = new JScrollPane(chipsWrap,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.setPreferredSize(new Dimension(innerWidth,
                    ResearchUiMetrics.BLACKLIST_DRAWER_MAX_HEIGHT));
            chipsArea = scroll;
        } else {
            chipsWrap.setPreferredSize(new Dimension(innerWidth, wrapHeight));
            chipsArea = chipsWrap;
        }

        JLabel header = new JLabel(skullAsText(ResearchUiTypography.semiBold(13f))
                ? SKULL + " Ausschlüsse" : "Ausschlüsse");
        header.setFont(ResearchUiTypography.semiBold(13f));
        header.setForeground(ResearchUiPalette.TEXT_PRIMARY);
        DrawerToggle closeToggle = new DrawerToggle();
        closeToggle.pointUp = false; // inside the open drawer the chevron points DOWN (= close)
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.add(header, BorderLayout.WEST);
        headerRow.add(closeToggle, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(false);
        content.add(headerRow, BorderLayout.NORTH);
        content.add(chipsArea, BorderLayout.CENTER);

        drawer = new JPopupMenu();
        drawer.setBackground(ResearchUiPalette.WORKSPACE_SURFACE);
        drawer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPalette.BORDER_WINDOW),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        drawer.setLayout(new BorderLayout());
        drawer.add(content, BorderLayout.CENTER);
        drawer.setPreferredSize(new Dimension(width,
                12 + headerRow.getPreferredSize().height + 10
                        + chipsArea.getPreferredSize().height + 12 + 2));
        drawer.show(this, 0, -drawer.getPreferredSize().height - 2); // grows UPWARD
        toggle.repaint();
    }

    /** Simulate the left-aligned wrap: how tall the chip flow becomes at this width. */
    private static int wrappedHeight(JPanel wrap, int width) {
        int rows = 1;
        int x = 0;
        for (java.awt.Component child : wrap.getComponents()) {
            int childWidth = child.getPreferredSize().width;
            int needed = (x > 0 ? x + ResearchUiMetrics.CHIP_GAP : 0) + childWidth;
            if (x > 0 && needed > width) {
                rows++;
                x = childWidth;
            } else {
                x = needed;
            }
        }
        return rows * ResearchUiMetrics.CHIP_HEIGHT + (rows - 1) * 8;
    }

    /** Swap the + button for an inline chip-sized text field: Enter records, Escape reverts. */
    private void beginInlineAdd(final JPanel chipsWrap, final ResearchPillButton addButton) {
        final JTextField field = new JTextField(18);
        field.setFont(ResearchUiTypography.regular(12f));
        field.setBackground(ResearchUiPalette.CHIP_SURFACE);
        field.setForeground(ResearchUiPalette.TEXT_PRIMARY);
        field.setCaretColor(ResearchUiPalette.TEXT_PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPalette.BORDER_DARK),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        final Runnable revert = () -> {
            chipsWrap.remove(field);
            chipsWrap.add(addButton);
            chipsWrap.revalidate();
            chipsWrap.repaint();
        };
        field.addActionListener(event -> {
            String value = field.getText().trim();
            revert.run();
            if (!value.isEmpty() && addAction != null) {
                addAction.accept(value); // the state listener re-renders strip + drawer
            }
        });
        field.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    revert.run();
                }
            }
        });
        chipsWrap.remove(addButton);
        chipsWrap.add(field);
        chipsWrap.revalidate();
        chipsWrap.repaint();
        field.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ compact row

    /**
     * The single compact chip row: lays out whole chips only. When not everything fits, the tail
     * collapses into the summary chip ({@code +N weitere}) — sized so IT always fits as well.
     */
    private final class CompactChipRow extends JPanel {

        private final List<ExclusionChip> chips = new ArrayList<ExclusionChip>();
        private final SummaryChip summary = new SummaryChip();

        CompactChipRow() {
            super(null); // manual layout: fit-or-hide is the whole point
            setOpaque(false);
            add(summary);
        }

        void rebuild() {
            for (ExclusionChip chip : chips) {
                remove(chip);
            }
            chips.clear();
            for (String exclusion : exclusions) {
                ExclusionChip chip = new ExclusionChip(exclusion);
                chips.add(chip);
                add(chip);
            }
            revalidate();
            repaint();
        }

        @Override
        public void doLayout() {
            int width = getWidth();
            int gap = ResearchUiMetrics.CHIP_GAP;
            // How many whole chips fit without help?
            int fitAll = fittingCount(width, chips.size());
            int visibleCount = fitAll;
            boolean needSummary = fitAll < chips.size();
            if (needSummary) {
                // Reserve room for the summary chip; shrink the visible count until BOTH fit.
                visibleCount = fitAll;
                while (visibleCount > 0) {
                    summary.setCount(chips.size() - visibleCount);
                    int used = usedWidth(visibleCount)
                            + (visibleCount > 0 ? gap : 0) + summary.getPreferredSize().width;
                    if (used <= width) {
                        break;
                    }
                    visibleCount--;
                }
                summary.setCount(chips.size() - visibleCount);
            }
            int x = 0;
            for (int index = 0; index < chips.size(); index++) {
                ExclusionChip chip = chips.get(index);
                boolean visible = index < visibleCount;
                chip.setVisible(visible);
                if (visible) {
                    Dimension pref = chip.getPreferredSize();
                    chip.setBounds(x, 0, pref.width, ResearchUiMetrics.CHIP_HEIGHT);
                    x += pref.width + gap;
                }
            }
            summary.setVisible(needSummary);
            if (needSummary) {
                Dimension pref = summary.getPreferredSize();
                summary.setBounds(x, 0, pref.width, ResearchUiMetrics.CHIP_HEIGHT);
            }
        }

        private int fittingCount(int width, int max) {
            int x = 0;
            for (int index = 0; index < max; index++) {
                int chipWidth = chips.get(index).getPreferredSize().width;
                int needed = (index > 0 ? x + ResearchUiMetrics.CHIP_GAP : 0) + chipWidth;
                if (needed > width) {
                    return index;
                }
                x = needed;
            }
            return max;
        }

        private int usedWidth(int count) {
            int x = 0;
            for (int index = 0; index < count; index++) {
                x += (index > 0 ? ResearchUiMetrics.CHIP_GAP : 0)
                        + chips.get(index).getPreferredSize().width;
            }
            return x;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, ResearchUiMetrics.CHIP_HEIGHT);
        }
    }

    /** The quiet {@code +N weitere} chip — same family, more muted; clicking opens the drawer. */
    private final class SummaryChip extends JComponent {

        private int count;
        private boolean hovered;

        SummaryChip() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    toggleDrawer();
                }
            });
        }

        void setCount(int count) {
            if (this.count != count) {
                this.count = count;
                setToolTipText(text() + " anzeigen");
            }
        }

        private String text() {
            return "+" + count + (count == 1 ? " weiterer" : " weitere");
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
                                : ResearchUiPainter.mix(ResearchUiPalette.BORDER_DARK,
                                        ResearchUiPalette.CHIP_SURFACE, 0.35f));
                g2.setFont(ResearchUiTypography.regular(12f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(hovered ? ResearchUiPalette.TEXT_PRIMARY
                        : ResearchUiPalette.TEXT_MUTED);
                g2.drawString(text(), ResearchUiMetrics.CHIP_PADDING_H,
                        (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.regular(12f));
            return new Dimension(metrics.stringWidth(text()) + 2 * ResearchUiMetrics.CHIP_PADDING_H,
                    ResearchUiMetrics.CHIP_HEIGHT);
        }
    }

    /** The small muted {@code Ausschlüsse ⌃} toggle bottom-right (⌄ while the drawer is open). */
    private final class DrawerToggle extends JComponent {

        boolean pointUp = true;
        private boolean hovered;

        DrawerToggle() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Alle Ausschlüsse anzeigen");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    toggleDrawer();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setFont(ResearchUiTypography.regular(11.5f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(hovered ? ResearchUiPalette.TEXT_PRIMARY
                        : ResearchUiPalette.TEXT_MUTED);
                int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString("Ausschlüsse", 0, textY);
                int chevronX = metrics.stringWidth("Ausschlüsse") + 10;
                boolean open = drawer != null && drawer.isVisible();
                if (pointUp && !open) {
                    ResearchUiPainter.paintChevronUp(g2, chevronX + 4, getHeight() / 2, 4,
                            g2.getColor());
                } else {
                    ResearchUiPainter.paintChevronDown(g2, chevronX + 4, getHeight() / 2, 4,
                            g2.getColor());
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.regular(11.5f));
            return new Dimension(metrics.stringWidth("Ausschlüsse") + 10 + 9 + 2, 16);
        }
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
            this.skullAsText = ResearchBlacklistPanel.skullAsText(
                    ResearchUiTypography.regular(12f));
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
            return getPreferredSize(); // never squeezed — the compact row hides instead
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
