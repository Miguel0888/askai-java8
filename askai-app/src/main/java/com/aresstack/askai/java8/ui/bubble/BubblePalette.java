package com.aresstack.askai.java8.ui.bubble;

import java.awt.Color;

/** Keep all bubble colors in one replaceable value object. */
public final class BubblePalette {

    private final Color transcriptBackground;
    private final Color userBackground;
    private final Color userForeground;
    private final Color assistantBackground;
    private final Color assistantForeground;
    private final Color activityBackground;
    private final Color activityForeground;
    private final Color activityAccent;
    private final Color failureAccent;
    private final Color infoForeground;

    public BubblePalette(Color transcriptBackground,
                         Color userBackground,
                         Color userForeground,
                         Color assistantBackground,
                         Color assistantForeground,
                         Color activityBackground,
                         Color activityForeground,
                         Color activityAccent,
                         Color failureAccent,
                         Color infoForeground) {
        this.transcriptBackground = requireColor(transcriptBackground, "transcriptBackground");
        this.userBackground = requireColor(userBackground, "userBackground");
        this.userForeground = requireColor(userForeground, "userForeground");
        this.assistantBackground = requireColor(assistantBackground, "assistantBackground");
        this.assistantForeground = requireColor(assistantForeground, "assistantForeground");
        this.activityBackground = requireColor(activityBackground, "activityBackground");
        this.activityForeground = requireColor(activityForeground, "activityForeground");
        this.activityAccent = requireColor(activityAccent, "activityAccent");
        this.failureAccent = requireColor(failureAccent, "failureAccent");
        this.infoForeground = requireColor(infoForeground, "infoForeground");
    }

    public static BubblePalette windowsPhoneInspired() {
        return new BubblePalette(
                new Color(0xF4F4F4),
                new Color(0x1676D2),
                Color.WHITE,
                new Color(0x15827A),
                Color.WHITE,
                new Color(0xF2C94C),
                new Color(0x252525),
                new Color(0xE39A18),
                new Color(0xC94C4C),
                new Color(0x6B6F76));
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

    public Color getActivityBackground() {
        return activityBackground;
    }

    public Color getActivityForeground() {
        return activityForeground;
    }

    public Color getActivityAccent() {
        return activityAccent;
    }

    public Color getFailureAccent() {
        return failureAccent;
    }

    public Color getInfoForeground() {
        return infoForeground;
    }

    private static Color requireColor(Color color, String name) {
        if (color == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return color;
    }
}
