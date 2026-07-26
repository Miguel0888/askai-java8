package com.aresstack.askai.java8.ui.bubble;

import com.aresstack.askai.java8.ui.markdown.MarkdownMessageView;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A left-pointing assistant speech bubble whose body is a native {@link MarkdownMessageView}
 * (headings, lists, code, tables, links, Mermaid). Unlike {@link SpeechBubblePanel} it does not
 * drive its own width from a text area: it lays out inside the transcript's vertical box so the
 * Markdown content wraps at the real available width. Only the bubble chrome is painted here.
 */
final class AssistantMarkdownBubble extends JPanel {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;

    private final Color bubbleColor;

    AssistantMarkdownBubble(BubblePalette palette, String header, MarkdownMessageView body) {
        this.bubbleColor = palette.getAssistantBackground();
        setOpaque(false);
        setLayout(new BorderLayout(0, 3));
        setBorder(new EmptyBorder(
                VERTICAL_PADDING,
                HORIZONTAL_PADDING + TAIL_WIDTH,
                VERTICAL_PADDING,
                HORIZONTAL_PADDING));
        JLabel headerLabel = createHeaderLabel(header, palette.getAssistantForeground());
        if (headerLabel.getText().length() > 0) {
            add(headerLabel, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
    }

    private static JLabel createHeaderLabel(String header, Color foreground) {
        JLabel label = new JLabel(header == null ? "" : header);
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(baseFont.deriveFont(Font.BOLD, Math.max(10f, baseFont.getSize2D() - 1f)));
        label.setForeground(withAlpha(foreground, 220));
        label.setOpaque(false);
        return label;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setComposite(AlphaComposite.SrcOver);
            copy.setColor(bubbleColor);
            copy.fill(createBubbleShape());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private java.awt.Shape createBubbleShape() {
        int bodyWidth = Math.max(1, getWidth() - TAIL_WIDTH);
        int bodyHeight = Math.max(1, getHeight());
        RoundRectangle2D body = new RoundRectangle2D.Float(TAIL_WIDTH, 0, bodyWidth, bodyHeight, ARC, ARC);

        int centerY = Math.max(VERTICAL_PADDING + 13, getHeight() - 22);
        Path2D tail = new Path2D.Float();
        int baseX = TAIL_WIDTH + 2;
        tail.moveTo(baseX, centerY - 8);
        tail.lineTo(1, centerY);
        tail.lineTo(baseX, centerY + 8);
        tail.closePath();

        java.awt.geom.Area shape = new java.awt.geom.Area(body);
        shape.add(new java.awt.geom.Area(tail));
        return shape;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
