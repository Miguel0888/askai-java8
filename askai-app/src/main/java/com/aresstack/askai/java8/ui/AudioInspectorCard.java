package com.aresstack.askai.java8.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Wrap the block inspector in a rounded card that rolls down when a block is selected and rolls up when
 * the selection clears. A small caret at the top points to the selected block's horizontal position, so
 * the card reads as dropping out of that block. The content keeps its full height while the card height
 * animates, so the reveal clips top-down rather than squashing the controls.
 *
 * <p>All state changes happen on the EDT (Swing {@link Timer}); no expensive work runs here.</p>
 */
final class AudioInspectorCard extends JPanel {

    private static final int CARET_HEIGHT = 9;
    private static final int CARET_HALF_WIDTH = 9;
    private static final int PAD_X = 12;
    private static final int PAD_TOP = 10;
    private static final int PAD_BOTTOM = 12;
    private static final int ARC = 14;
    private static final double STEP = 0.16d;

    private final JComponent content;
    private final Timer timer;
    private double fraction;       // 0 = fully collapsed, 1 = fully expanded
    private boolean expanding;
    private int caretX = -1;       // caret tip x in card coordinates, or -1 to hide it

    AudioInspectorCard(JComponent content) {
        this.content = content;
        setOpaque(false);
        setLayout(null); // the content is positioned manually so the card can clip it while animating
        content.setOpaque(false);
        content.setVisible(false);
        add(content);
        timer = new Timer(15, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                tick();
            }
        });
    }

    /** Points the caret at {@code x} (card coordinates); pass a negative value to hide it. */
    void setCaretX(int x) {
        caretX = x;
        repaint();
    }

    /** Rolls the card down (expanded) or up (collapsed); animation runs to completion either way. */
    void setExpanded(boolean expanded) {
        if (expanded == expanding && (expanded ? fraction >= 1.0d : fraction <= 0.0d)) {
            return;
        }
        expanding = expanded;
        if (expanded) {
            content.setVisible(true);
        }
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    private void tick() {
        fraction += expanding ? STEP : -STEP;
        if (fraction >= 1.0d) {
            fraction = 1.0d;
            timer.stop();
        } else if (fraction <= 0.0d) {
            fraction = 0.0d;
            timer.stop();
            content.setVisible(false);
        }
        revalidate();
        if (getParent() != null) {
            getParent().revalidate();
        }
        repaint();
    }

    private int contentHeight(int innerWidth) {
        // Lay the content out at the target width first, so a wrapping parameter row reports the real
        // height it needs at that width before we measure it.
        content.setSize(innerWidth, 1);
        content.doLayout();
        return content.getPreferredSize().height;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : 760;
        int inner = Math.max(0, width - 2 * PAD_X);
        int full = CARET_HEIGHT + PAD_TOP + contentHeight(inner) + PAD_BOTTOM;
        return new Dimension(width, (int) Math.round(full * fraction));
    }

    @Override
    public void doLayout() {
        int inner = Math.max(0, getWidth() - 2 * PAD_X);
        content.setBounds(PAD_X, CARET_HEIGHT + PAD_TOP, inner, contentHeight(inner));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getHeight() <= CARET_HEIGHT) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = uiColor("Panel.background", Color.WHITE);
            Color border = uiColor("Component.borderColor", uiColor("controlShadow", Color.GRAY));
            int top = CARET_HEIGHT;
            int width = getWidth();
            int height = getHeight() - CARET_HEIGHT;

            g.setColor(fill);
            g.fillRoundRect(0, top, width - 1, height - 1, ARC, ARC);
            g.setColor(border);
            g.drawRoundRect(0, top, width - 1, height - 1, ARC, ARC);

            // The caret that ties the card to the selected block.
            if (caretX >= 0) {
                int tipX = Math.max(CARET_HALF_WIDTH + 1, Math.min(width - CARET_HALF_WIDTH - 1, caretX));
                int[] xs = {tipX - CARET_HALF_WIDTH, tipX, tipX + CARET_HALF_WIDTH};
                int[] ys = {top + 1, 0, top + 1};
                g.setColor(fill);
                g.fillPolygon(xs, ys, 3);
                g.setColor(border);
                g.drawLine(xs[0], ys[0], xs[1], ys[1]);
                g.drawLine(xs[1], ys[1], xs[2], ys[2]);
            }
        } finally {
            g.dispose();
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
}
