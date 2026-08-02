package com.aresstack.askai.research.agent;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * One search-suggestion TAG: a Java2D rounded chip in the composer-button style, filled with the
 * SAME yellow the host paints MCP tool-call / thought bubbles with (BubblePalette activity colors:
 * background 0xF2C94C, text 0x252525), hover brightens, press darkens. The plugin cannot reach the
 * host's private ComposerButton, so the look is mirrored here.
 */
final class ResearchTagButton extends JButton {

    private static final Color TAG_BACKGROUND = new Color(0xF2C94C);
    private static final Color TAG_FOREGROUND = new Color(0x252525);

    ResearchTagButton(String text) {
        super(text);
        setFont(getFont().deriveFont(Font.BOLD, 11.5f));
        setForeground(TAG_FOREGROUND);
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
        Color fill = TAG_BACKGROUND;
        if (getModel().isPressed()) {
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
