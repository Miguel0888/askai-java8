package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.JButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * A scroll pane whose scrollbars speak the comic language quietly: no arrow buttons, a
 * transparent track and a slim rounded ink thumb. Scrolling behavior is untouched — this is
 * purely the {@link BasicScrollBarUI} repainted.
 */
public class ComicScrollPane extends JScrollPane {

    private static final int BAR_THICKNESS = 10;

    public ComicScrollPane(Component view, int vsbPolicy, int hsbPolicy) {
        this(view, vsbPolicy, hsbPolicy, ComicPalette.defaultPalette());
    }

    public ComicScrollPane(Component view, int vsbPolicy, int hsbPolicy, ComicPalette palette) {
        super(view, vsbPolicy, hsbPolicy);
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        setBorder(null);
        styleBar(getVerticalScrollBar(), palette);
        styleBar(getHorizontalScrollBar(), palette);
    }

    private static void styleBar(JScrollBar bar, ComicPalette palette) {
        bar.setUI(new ComicScrollBarUI(palette));
        bar.setOpaque(false);
        bar.setPreferredSize(bar.getOrientation() == JScrollBar.VERTICAL
                ? new Dimension(BAR_THICKNESS, 0) : new Dimension(0, BAR_THICKNESS));
    }

    private static final class ComicScrollBarUI extends BasicScrollBarUI {

        private final ComicPalette palette;

        ComicScrollBarUI(ComicPalette palette) {
            this.palette = palette;
        }

        @Override
        protected void paintTrack(Graphics g, javax.swing.JComponent c, Rectangle bounds) {
            // transparent — the surrounding surface stays calm
        }

        @Override
        protected void paintThumb(Graphics g, javax.swing.JComponent c, Rectangle bounds) {
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
}
