package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * One TAG chip: a Java2D rounded chip in the composer-button style. Suggestions use the SAME yellow the host
 * paints MCP tool-call / thought bubbles with (BubblePalette activity colors: background 0xF2C94C, text
 * 0x252525); ACTION tags use the same chip in RED — one uniform look for everything clickable above the
 * composer. Hover brightens, press darkens, disabled fades. The plugin cannot reach the host's private
 * ComposerButton, so the look is mirrored here.
 */
final class ResearchTagButton extends JButton {

    static final Color SUGGESTION_BACKGROUND = ThemeColor.of("bubble.activityBackground");
    static final Color SUGGESTION_FOREGROUND = ThemeColor.of("bubble.activityForeground");
    /** The action red — the bubble palette's alert tone, same chip geometry as the yellow suggestion. */
    static final Color ACTION_BACKGROUND = ThemeColor.of("research.actionTagBackground");
    static final Color ACTION_FOREGROUND = ThemeColor.of("bubble.assistantForeground");

    private final Color background;

    ResearchTagButton(String text) {
        this(text, SUGGESTION_BACKGROUND, SUGGESTION_FOREGROUND);
    }

    ResearchTagButton(String text, Color background, Color foreground) {
        super(text);
        this.background = background;
        setFont(getFont().deriveFont(Font.BOLD, 11.5f));
        setForeground(foreground);
        setFocusable(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMargin(new java.awt.Insets(3, 10, 3, 10));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 10, 3, 10));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = background;
        if (!isEnabled()) {
            fill = ThemeColor.withAlpha(fill, 110); // faded, still readable
        } else if (getModel().isPressed()) {
            fill = fill.darker();
        } else if (getModel().isRollover()) {
            fill = fill.brighter();
        }
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g2.dispose();
        super.paintComponent(graphics);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(size.width, Math.max(24, size.height));
    }
}
