package com.aresstack.askai.java8.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Wrap the block inspector in a rounded, block-width card that rolls down when a block is selected and
 * rolls up when the selection clears. A caret at the top points to the block, so the card reads as
 * dropping out of it. The settings are stacked vertically in a scroll pane, so a narrow block-width card
 * simply scrolls instead of trying to fill the whole editor width.
 *
 * <p>During the animation the content keeps its full height and the card clips it, so the reveal grows
 * top-down rather than squashing the controls. All state changes run on the EDT.</p>
 */
final class AudioInspectorCard extends JPanel {

    private static final int CARET_HEIGHT = 9;
    private static final int CARET_HALF_WIDTH = 9;
    private static final int PAD_X = 10;
    private static final int PAD_TOP = 8;
    private static final int PAD_BOTTOM = 10;
    private static final int ARC = 14;
    private static final int MAX_HEIGHT = 260;
    private static final double STEP = 0.16d;

    private final int cardWidth;
    private final JScrollPane scroll;
    private final Timer timer;
    private double fraction;       // 0 = fully collapsed, 1 = fully expanded
    private boolean expanding;
    private int caretX = -1;       // caret tip x in card coordinates, or -1 to hide it

    AudioInspectorCard(JComponent content, int cardWidth) {
        this.cardWidth = cardWidth;
        setOpaque(false);
        setLayout(null); // the scroll pane is positioned manually so the card can clip it while animating
        content.setOpaque(false);
        scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setVisible(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll);
        timer = new Timer(15, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                tick();
            }
        });
    }

    int cardWidth() {
        return cardWidth;
    }

    /** @return whether the settings content is currently shown (revealing or open). */
    boolean isContentShown() {
        return scroll.isVisible();
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
            scroll.setVisible(true);
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
            scroll.setVisible(false);
        }
        revalidate();
        if (getParent() != null) {
            getParent().revalidate();
        }
        repaint();
    }

    /** @return the interior (viewport) height the content wants at the card width, capped to MAX_HEIGHT. */
    private int interiorHeight() {
        int inner = Math.max(0, cardWidth - 2 * PAD_X);
        Component view = scroll.getViewport().getView();
        if (view == null) {
            return 0;
        }
        // Lay the content out at the interior width first, so stacked/wrapped controls report a real height.
        view.setSize(inner, 1);
        view.doLayout();
        int cap = MAX_HEIGHT - CARET_HEIGHT - PAD_TOP - PAD_BOTTOM;
        return Math.min(view.getPreferredSize().height, cap);
    }

    @Override
    public Dimension getPreferredSize() {
        int full = CARET_HEIGHT + PAD_TOP + interiorHeight() + PAD_BOTTOM;
        return new Dimension(cardWidth, (int) Math.round(full * fraction));
    }

    @Override
    public void doLayout() {
        int inner = Math.max(0, cardWidth - 2 * PAD_X);
        // Give the scroll pane its FULL interior height; the shorter (animating) card clips it, so the
        // reveal grows top-down and a too-tall content simply shows the scroll bar once fully open.
        scroll.setBounds(PAD_X, CARET_HEIGHT + PAD_TOP, inner, interiorHeight());
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
