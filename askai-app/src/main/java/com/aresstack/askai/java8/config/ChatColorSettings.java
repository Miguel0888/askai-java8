package com.aresstack.askai.java8.config;

import java.awt.Color;

/**
 * The user-adjustable chat bubble colors (transcript background and the two message bubbles). Kept as a
 * small immutable value object so it threads through {@link AppConfiguration} like any other setting; the
 * UI maps it onto the full bubble palette (activity/failure/info colors keep their defaults).
 *
 * <p>Defaults match the shipped Windows-Phone-inspired palette, so an install with no stored colors looks
 * exactly as before.</p>
 */
public final class ChatColorSettings {

    private final Color transcriptBackground;
    private final Color userBackground;
    private final Color userForeground;
    private final Color assistantBackground;
    private final Color assistantForeground;

    public ChatColorSettings(Color transcriptBackground, Color userBackground, Color userForeground,
                             Color assistantBackground, Color assistantForeground) {
        this.transcriptBackground = orDefault(transcriptBackground, 0xF4F4F4);
        this.userBackground = orDefault(userBackground, 0x1676D2);
        this.userForeground = orDefault(userForeground, 0xFFFFFF);
        this.assistantBackground = orDefault(assistantBackground, 0x15827A);
        this.assistantForeground = orDefault(assistantForeground, 0xFFFFFF);
    }

    /** @return the shipped defaults (the Windows-Phone-inspired chat colors). */
    public static ChatColorSettings defaults() {
        return new ChatColorSettings(new Color(0xF4F4F4), new Color(0x1676D2), new Color(0xFFFFFF),
                new Color(0x15827A), new Color(0xFFFFFF));
    }

    public Color getTranscriptBackground() {
        return transcriptBackground;
    }

    public Color getUserBackground() {
        return userBackground;
    }

    public Color getUserForeground() {
        return userForeground;
    }

    public Color getAssistantBackground() {
        return assistantBackground;
    }

    public Color getAssistantForeground() {
        return assistantForeground;
    }

    public ChatColorSettings withTranscriptBackground(Color color) {
        return new ChatColorSettings(color, userBackground, userForeground, assistantBackground, assistantForeground);
    }

    public ChatColorSettings withUserBackground(Color color) {
        return new ChatColorSettings(transcriptBackground, color, userForeground, assistantBackground, assistantForeground);
    }

    public ChatColorSettings withUserForeground(Color color) {
        return new ChatColorSettings(transcriptBackground, userBackground, color, assistantBackground, assistantForeground);
    }

    public ChatColorSettings withAssistantBackground(Color color) {
        return new ChatColorSettings(transcriptBackground, userBackground, userForeground, color, assistantForeground);
    }

    public ChatColorSettings withAssistantForeground(Color color) {
        return new ChatColorSettings(transcriptBackground, userBackground, userForeground, assistantBackground, color);
    }

    /** Parses a {@code RRGGBB} (or {@code #RRGGBB}) hex string, or returns {@code fallback} when invalid. */
    public static Color parseHex(String value, Color fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() == 0) {
            return fallback;
        }
        try {
            return new Color(Integer.parseInt(trimmed, 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** @return the color as a 6-digit upper-case {@code RRGGBB} hex string (no alpha). */
    public static String toHex(Color color) {
        return String.format("%06X", color.getRGB() & 0xFFFFFF);
    }

    private static Color orDefault(Color color, int fallbackRgb) {
        return color == null ? new Color(fallbackRgb) : color;
    }
}
