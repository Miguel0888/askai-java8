package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.ComicPalette;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/**
 * The third search-bar variant: a {@link ComicSearchBar} dressed as a YELLOW suggestion tag —
 * same chip yellow, same rounded-chip arc, ink contour and ink magnifier — but with a real text
 * field, so the user can type a query and fire it directly (the tag surface's typed twin of a
 * suggestion click). Compact by design: it sits inline in a tag flow, not in a toolbar.
 */
public class ComicSearchTag extends ComicSearchBar {

    /** Matches the suggestion chips' rounding so the tag row reads as one family. */
    private static final int TAG_ARC = 14;
    private static final int MIN_WIDTH = 200;

    public ComicSearchTag(String placeholder, String tooltip) {
        this(placeholder, tooltip, ComicPalette.defaultPalette());
    }

    public ComicSearchTag(String placeholder, String tooltip, ComicPalette palette) {
        super(placeholder, tooltip,
                palette.getAccentYellow(), palette.getAccentYellow().brighter(),
                palette.getInk(), palette.getInk(),
                palette.getInk(), withAlpha(palette.getInk(), 140), TAG_ARC, 1.4f);
        getTextField().setForeground(palette.getInk());
        getTextField().setCaretColor(palette.getInk());
        getTextField().setFont(getTextField().getFont().deriveFont(Font.BOLD, 11.5f));
        getGoButton().setForeground(palette.getInk());
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(MIN_WIDTH, size.width), Math.max(24, size.height));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
