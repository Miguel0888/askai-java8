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
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A left-pointing assistant speech bubble whose body is a native {@link MarkdownMessageView}
 * (headings, lists, code, tables, links, Mermaid). Unlike {@link SpeechBubblePanel} it does not
 * drive its own width from a text area: it lays out inside the transcript's vertical box so the
 * Markdown content wraps at the real available width. Only the bubble chrome is painted here.
 */
final class AssistantMarkdownBubble extends JPanel
        implements com.aresstack.askai.java8.ui.markdown.WidthAwareHeight {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;
    private static final int BODY_GAP = 3;

    private final Color bubbleColor;
    private final MarkdownMessageView body;
    private final JLabel headerLabel;
    private final boolean headerShown;

    AssistantMarkdownBubble(BubblePalette palette, String header, MarkdownMessageView body) {
        this.bubbleColor = palette.getAssistantBackground();
        this.body = body;
        setOpaque(false);
        setLayout(new BorderLayout(0, BODY_GAP));
        // This branch's assistant bubbles always carry the tail on the LEFT (no BubbleSide refactor here).
        setBorder(new EmptyBorder(
                VERTICAL_PADDING,
                HORIZONTAL_PADDING + TAIL_WIDTH,
                VERTICAL_PADDING,
                HORIZONTAL_PADDING));
        this.headerLabel = createHeaderLabel(header, palette.getAssistantForeground());
        this.headerShown = headerLabel.getText().length() > 0;
        if (headerShown) {
            add(headerLabel, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
    }

    /** Correct height for a fixed bubble width: chrome + optional header + the Markdown body at that width. */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(1, width - insets.left - insets.right);
        int height = insets.top + insets.bottom + body.preferredHeightForWidth(innerWidth);
        if (headerShown) {
            height += headerLabel.getPreferredSize().height + BODY_GAP;
        }
        return height;
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
