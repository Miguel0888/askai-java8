package com.aresstack.comiccontrols.theme;

import java.awt.Color;

/**
 * The ONE color table of the dark research-UI design study (phase selector, chats footer, blacklist
 * chips, …). New Java2D controls read their colors from here instead of hardcoding hex values per
 * class — the study's palette must stay changeable in exactly one place.
 */
public final class ResearchUiPalette {

    /** Deepest window background of the study. */
    public static final Color DARK_BASE = new Color(0x0C1117);
    /** Raised dark surface (cards, popups). */
    public static final Color DARK_SURFACE = new Color(0x12181F);
    /** The workspace strip under the composer (blacklist area). */
    public static final Color WORKSPACE_SURFACE = new Color(0x1B2028);
    /** Chip body fill. */
    public static final Color CHIP_SURFACE = new Color(0x0E1218);
    /** Secondary dark controls (buttons, language pill, add chip). */
    public static final Color SECONDARY_SURFACE = new Color(0x141A21);

    public static final Color TEXT_PRIMARY = new Color(0xE8EBF2);
    public static final Color TEXT_MUTED = new Color(0xADB5C7);
    public static final Color TEXT_DARK = new Color(0x171C29);

    public static final Color PURPLE_PRIMARY = new Color(0x5E3BA3);
    public static final Color PURPLE_ACTION = new Color(0x754DB8);
    /** Hover shade between primary and action purple (phase pill hover). */
    public static final Color PURPLE_HOVER = new Color(0x6943AD);

    public static final Color BORDER_DARK = new Color(0x5E6370);
    public static final Color BORDER_WINDOW = new Color(0x38404D);

    /** Hover fill of secondary dark controls. */
    public static final Color SECONDARY_HOVER = new Color(0x202832);
    /** Hovered chip body / hovered chip border. */
    public static final Color CHIP_HOVER_SURFACE = new Color(0x151B23);
    public static final Color CHIP_HOVER_BORDER = new Color(0x767D89);
    /** Round hover backdrop behind a chip's ✕. */
    public static final Color CHIP_CLOSE_HOVER = new Color(0x2A3039);
    /** The subtle divider on top of the blacklist area. */
    public static final Color WORKSPACE_DIVIDER = new Color(0x282E38);

    private ResearchUiPalette() {
    }
}
