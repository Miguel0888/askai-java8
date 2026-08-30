package com.aresstack.comiccontrols.theme;

/**
 * The pixel metrics of the dark research-UI design study — radii, control heights and paddings.
 * Like {@link ResearchUiPalette}: controls read from here, never own local copies.
 */
public final class ResearchUiMetrics {

    /** Standard corner radius of normal controls (pills, footer buttons). */
    public static final int RADIUS_CONTROL = 10;
    /** Corner radius of secondary controls (the "+ Hinzufügen" chip). */
    public static final int RADIUS_SECONDARY = 12;
    /** Corner radius of the blacklist chips. */
    public static final int RADIUS_CHIP = 18;

    /** "+ Neuer Chat" button. */
    public static final int NEW_CHAT_HEIGHT = 36;
    public static final int NEW_CHAT_PADDING_H = 14;

    /** Chats footer strip. */
    public static final int FOOTER_HEIGHT = 52;
    public static final int FOOTER_PADDING_H = 12;
    public static final int FOOTER_PADDING_V = 8;
    public static final int FOOTER_CONTROL_HEIGHT = 34;
    public static final int FOOTER_ICON_BUTTON = 34;
    public static final int FOOTER_PILL_PADDING_H = 12;

    /** Phase selector pill. */
    public static final int PHASE_PILL_HEIGHT = 34;
    public static final int PHASE_PILL_MIN_WIDTH = 240;
    public static final int PHASE_PILL_REFERENCE_WIDTH = 266;
    public static final int PHASE_PILL_PADDING_LEFT = 16;
    public static final int PHASE_PILL_PADDING_RIGHT = 14;
    public static final int PHASE_PILL_CHEVRON_GAP = 12;

    /** Blacklist area under the composer (compact strip + upward drawer). */
    public static final int BLACKLIST_HEIGHT = 90;
    public static final int BLACKLIST_PADDING_LEFT = 14;
    public static final int BLACKLIST_PADDING_RIGHT = 14;
    public static final int BLACKLIST_PADDING_TOP = 12;
    public static final int BLACKLIST_PADDING_BOTTOM = 12;
    /** The expanded drawer scrolls vertically once its wrapped chips exceed this height. */
    public static final int BLACKLIST_DRAWER_MAX_HEIGHT = 240;

    /** Blacklist chips. */
    public static final int CHIP_HEIGHT = 43;
    public static final int CHIP_PADDING_H = 15;
    public static final int CHIP_SKULL_TEXT_GAP = 8;
    public static final int CHIP_TEXT_CLOSE_GAP = 10;
    public static final int CHIP_GAP = 12;
    public static final int CHIP_CLOSE_HIT = 20;

    /** "+ Hinzufügen" control. */
    public static final int ADD_CHIP_HEIGHT = 43;
    public static final int ADD_CHIP_PADDING_H = 12;

    /** Chat-history rows in the drawer (light chrome, two lines). */
    public static final int CHAT_ROW_HEIGHT = 48;
    public static final int CHAT_ROW_RADIUS = 10;
    public static final int CHAT_ROW_PADDING_H = 12;
    /** The 3px vertical accent bar marking the selected row. */
    public static final int CHAT_ROW_ACCENT_WIDTH = 3;

    private ResearchUiMetrics() {
    }
}
