package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.ComicPalette;

import java.awt.Color;
import java.awt.Dimension;

/**
 * The third search-bar variant: the AMBER-rimmed twin of the navigation-blue default (MainframeMate
 * kept the same two-variant scheme — blue for navigation, amber for the warm action bar). Same calm
 * surface and geometry as {@link ComicSearchBar}, but the amber contour is ALWAYS visible (this bar
 * fires real web searches, so it announces itself), the magnifier is amber too, and focusing warms
 * the surface to the find bar's cream. Compact by design: it sits inline in a tag flow, not in a
 * toolbar. (Historical note: v1 filled the whole chip suggestion-yellow — far too loud next to the
 * calm blue bars.)
 */
public class ComicSearchTag extends ComicSearchBar {

    /** Matches the suggestion chips' rounding so the tag row reads as one family. */
    private static final int TAG_ARC = 14;
    private static final int MIN_WIDTH = 200;

    /** The find bar's warm cream — the shared "amber family" focus surface. */
    private static final Color WARM_FOCUS_BACKGROUND = new Color(0xFFF8E1);

    public ComicSearchTag(String placeholder, String tooltip) {
        this(placeholder, tooltip, ComicPalette.defaultPalette());
    }

    public ComicSearchTag(String placeholder, String tooltip, ComicPalette palette) {
        super(placeholder, tooltip,
                palette.getSurface(), WARM_FOCUS_BACKGROUND,
                palette.getAccentOrange(), darken(palette.getAccentOrange()),
                palette.getAccentOrange(), new Color(0xAAAAAA), TAG_ARC, 1.4f);
        getTextField().setForeground(palette.getInk());
        getTextField().setCaretColor(palette.getInk());
        getGoButton().setForeground(palette.getAccentOrange());
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(MIN_WIDTH, size.width), Math.max(24, size.height));
    }

    private static Color darken(Color color) {
        return new Color(Math.round(color.getRed() * 0.82f),
                Math.round(color.getGreen() * 0.82f),
                Math.round(color.getBlue() * 0.82f));
    }
}
