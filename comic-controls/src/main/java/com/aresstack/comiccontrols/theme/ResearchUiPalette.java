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

    /**
     * THE AskAI accent blue of the active navigation (the highlighted Chats ribbon entry / composer
     * primary — {@code ChatComposerPanel} reads its PRIMARY from here). The phase selector and the
     * chat-list selection use exactly this token; no second blue palette exists.
     */
    public static final Color ACCENT_BLUE = new Color(0x2979FF);
    /** The destructive red of the composer family (delete-all hover, stop button). */
    public static final Color DANGER_RED = new Color(0xD32F2F);

    /** Light-chrome tokens for design-study controls embedded in the BRIGHT AskAI surfaces. */
    public static final Color LIGHT_CONTROL_BG = Color.WHITE;
    public static final Color LIGHT_CONTROL_BORDER = new Color(0xC9CED6);
    public static final Color LIGHT_CONTROL_TEXT = new Color(0x44484D);
    public static final Color LIGHT_TEXT_MUTED = new Color(0x8A909B);

    /** Hover fill of secondary dark controls. */
    public static final Color SECONDARY_HOVER = new Color(0x202832);
    /** Hovered chip body / hovered chip border. */
    public static final Color CHIP_HOVER_SURFACE = new Color(0x151B23);
    public static final Color CHIP_HOVER_BORDER = new Color(0x767D89);
    /** Round hover backdrop behind a chip's ✕. */
    public static final Color CHIP_CLOSE_HOVER = new Color(0x2A3039);
    /** The subtle divider on top of dark workspace strips. */
    public static final Color WORKSPACE_DIVIDER = new Color(0x282E38);

    // ------------------------------------------------------------------ out-of-scope sky
    // Deliberately air-like, harmonizing with the bright AskAI surfaces — never a dominant blue.

    /** The sky's top tone; it fades to fully transparent towards the chat. */
    public static final Color SKY_TOP = new Color(0xF5FAFE);
    /**
     * The COLLAPSED sky status bar: a firmer, calm sky blue so the bar reads as a deliberate
     * control — clearly stronger than the airy near-white of the open sky.
     */
    public static final Color SKY_BAR_SURFACE = new Color(0xBFDCF2);
    /** The quiet caption ("Außerhalb des Scopes"). */
    public static final Color SKY_CAPTION = new Color(0x6E87A2);
    /** Cloud chips: light body, soft blue border, calm slate text. */
    public static final Color CLOUD_SURFACE = new Color(0xF7FBFF);
    public static final Color CLOUD_BORDER = new Color(0xAFCDE8);
    public static final Color CLOUD_TEXT = new Color(0x40556B);
    public static final Color CLOUD_HOVER_SURFACE = new Color(0xEAF4FC);
    public static final Color CLOUD_HOVER_BORDER = new Color(0x8FB8DE);
    /** The remove ✕ inside a cloud chip (hover uses {@link #CLOUD_TEXT}). */
    public static final Color CLOUD_CLOSE = new Color(0x72879A);

    private ResearchUiPalette() {
    }
}
