package com.aresstack.askai.java8.ui.markdown;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Define visual values for rendered Markdown without coupling to a concrete look and feel. */
public final class MarkdownTheme {

    private final Font bodyFont;
    private final Font codeFont;
    private final Color foreground;
    private final Color mutedForeground;
    private final Color linkForeground;
    private final Color codeBackground;
    private final Color quoteBorder;
    private final Color separatorColor;
    private final Color errorForeground;

    public MarkdownTheme(Font bodyFont,
                         Font codeFont,
                         Color foreground,
                         Color mutedForeground,
                         Color linkForeground,
                         Color codeBackground,
                         Color quoteBorder,
                         Color separatorColor,
                         Color errorForeground) {
        this.bodyFont = bodyFont;
        this.codeFont = codeFont;
        this.foreground = foreground;
        this.mutedForeground = mutedForeground;
        this.linkForeground = linkForeground;
        this.codeBackground = codeBackground;
        this.quoteBorder = quoteBorder;
        this.separatorColor = separatorColor;
        this.errorForeground = errorForeground;
    }

    public static MarkdownTheme fromUiDefaults() {
        Font labelFont = UIManager.getFont("Label.font");
        Font textFont = UIManager.getFont("TextArea.font");
        Color foreground = color("Label.foreground", Color.DARK_GRAY);
        Color muted = color("Label.disabledForeground", new Color(0x777777));
        Color link = color("Component.linkColor", new Color(0x1565C0));
        Color background = color("TextArea.background", Color.WHITE);
        Color codeBackground = blend(background, foreground, 0.06f);
        Color quoteBorder = blend(background, foreground, 0.22f);
        Color separator = blend(background, foreground, 0.16f);
        Color error = color("Actions.Red", new Color(0xC62828));
        Font body = labelFont == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : labelFont;
        Font code = textFont == null
                ? new Font(Font.MONOSPACED, Font.PLAIN, body.getSize())
                : new Font(Font.MONOSPACED, Font.PLAIN, textFont.getSize());
        return new MarkdownTheme(body, code, foreground, muted, link, codeBackground,
                quoteBorder, separator, error);
    }

    private static Color color(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private static Color blend(Color first, Color second, float secondWeight) {
        float firstWeight = 1.0f - secondWeight;
        return new Color(
                Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
                Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
                Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    public Font getBodyFont() {
        return bodyFont;
    }

    public Font getCodeFont() {
        return codeFont;
    }

    public Color getForeground() {
        return foreground;
    }

    public Color getMutedForeground() {
        return mutedForeground;
    }

    public Color getLinkForeground() {
        return linkForeground;
    }

    public Color getCodeBackground() {
        return codeBackground;
    }

    public Color getQuoteBorder() {
        return quoteBorder;
    }

    public Color getSeparatorColor() {
        return separatorColor;
    }

    public Color getErrorForeground() {
        return errorForeground;
    }
}
