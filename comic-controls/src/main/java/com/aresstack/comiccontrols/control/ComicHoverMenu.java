package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.paint.ComicImpactPainter;
import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.JMenu;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A top-level menu with a comic-impact HOVER accent. In its normal state it is a plain
 * look-and-feel {@link JMenu} (same font, same text color, no permanent burst); while the mouse
 * hovers it, a {@link ComicImpactPainter} plate appears behind the title and the text switches to
 * the palette's ink so it stays readable on the yellow/orange fill. Leaving the menu returns it to
 * the plain look immediately — there is no animation.
 *
 * <p>While the menu is SELECTED (its popup is open) the look and feel's normal selection highlight
 * wins over the hover plate; dropdown {@code JMenuItem}s are untouched by design.</p>
 */
public class ComicHoverMenu extends JMenu {

    private final ComicPalette palette;
    private final ComicImpactPainter painter;
    private boolean hoverActive;

    public ComicHoverMenu(String text) {
        this(text, ComicPalette.defaultPalette());
    }

    public ComicHoverMenu(String text, ComicPalette palette) {
        super(text);
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
        this.painter = new ComicImpactPainter(palette);
        // Non-opaque: the menu bar shows through in the normal state, and the hover plate can be
        // painted UNDER the look and feel's text without fighting an opaque background fill.
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                setComicHoverActive(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                setComicHoverActive(false);
            }
        });
    }

    /** Whether the comic hover accent is currently shown (mouse inside the menu title). */
    public boolean isComicHoverActive() {
        return hoverActive;
    }

    @Override
    public Color getForeground() {
        // The UI delegate reads the foreground while painting the title; on hover the text must be
        // ink-dark regardless of the look and feel so it stays readable on the yellow plate.
        // (Guard: the superclass constructor paints/queries before our fields exist.)
        if (hoverActive && palette != null && !isSelected()) {
            return palette.getInk();
        }
        return super.getForeground();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (hoverActive && !isSelected()) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                painter.paint(g2, getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }

    private void setComicHoverActive(boolean active) {
        if (hoverActive != active) {
            hoverActive = active;
            repaint();
        }
    }
}
