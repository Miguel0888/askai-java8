package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * THE AskAI scrollbar delegate — extracted from the chat-history drawer so every scroll surface
 * (chat transcript, history, artifact panes, the out-of-scope sky, …) shares ONE implementation:
 * no arrow buttons, no bevel, a transparent track and a slim rounded ink thumb that warms up on
 * hover/drag. Scrolling behavior is untouched; this is purely the {@link BasicScrollBarUI}
 * repainted. Use {@link #install(JScrollBar)} instead of re-styling bars by hand.
 */
public final class ComicScrollBarUI extends BasicScrollBarUI {

    /** The shared bar thickness (slim, calm). */
    public static final int BAR_THICKNESS = 10;

    private final ComicPalette palette;

    public ComicScrollBarUI(ComicPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
    }

    /** Apply the shared look to a bar: this UI, transparent, slim preferred size. */
    public static void install(JScrollBar bar) {
        install(bar, ComicPalette.defaultPalette());
    }

    public static void install(JScrollBar bar, ComicPalette palette) {
        bar.setUI(new ComicScrollBarUI(palette));
        bar.setOpaque(false);
        bar.setPreferredSize(bar.getOrientation() == JScrollBar.VERTICAL
                ? new Dimension(BAR_THICKNESS, 0) : new Dimension(0, BAR_THICKNESS));
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
        // transparent — the surrounding surface stays calm
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
        if (bounds.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isDragging || isThumbRollover()
                    ? palette.getAccentOrange() : palette.getInk());
            g2.fillRoundRect(bounds.x + 2, bounds.y + 2,
                    bounds.width - 4, bounds.height - 4, 6, 6);
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zeroButton();
    }

    private static JButton zeroButton() {
        JButton none = new JButton();
        Dimension zero = new Dimension(0, 0);
        none.setPreferredSize(zero);
        none.setMinimumSize(zero);
        none.setMaximumSize(zero);
        none.setFocusable(false);
        return none;
    }
}
