package com.aresstack.askai.java8.ui.bubble;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Render one selectable, streamable chat message inside a painted speech bubble.
 *
 * <p>Keep the text as a real Swing text component so copy and selection continue to work. Paint
 * only the bubble chrome with {@link Graphics2D}.</p>
 *
 * <p>Implements {@link com.aresstack.askai.java8.ui.markdown.WidthAwareHeight} so the transcript
 * rows can ask for the exact wrapped height at the final bubble width — before that, long
 * unbroken texts were measured unwrapped once and rendered as a single clipped line.</p>
 */
public final class SpeechBubblePanel extends JPanel
        implements com.aresstack.askai.java8.ui.markdown.WidthAwareHeight {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;
    private static final int DEFAULT_MAXIMUM_WIDTH = 580;
    private static final int MINIMUM_WIDTH = 104;

    private final BubbleSide side;
    private final Color bubbleColor;
    private final Color textColor;
    private final JLabel headerLabel;
    private final JTextArea textArea;
    private int maximumBubbleWidth;

    public SpeechBubblePanel(BubbleSide side,
                             Color bubbleColor,
                             Color textColor,
                             String header,
                             String text) {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (bubbleColor == null) {
            throw new IllegalArgumentException("bubbleColor must not be null");
        }
        if (textColor == null) {
            throw new IllegalArgumentException("textColor must not be null");
        }
        this.side = side;
        this.bubbleColor = bubbleColor;
        this.textColor = textColor;
        this.maximumBubbleWidth = DEFAULT_MAXIMUM_WIDTH;
        this.headerLabel = createHeaderLabel(header);
        this.textArea = createTextArea(text);
        buildUi();
    }

    public BubbleSide getSide() {
        return side;
    }

    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.setText(normalize(text));
        refreshLayout();
    }

    public void appendText(String delta) {
        if (delta == null || delta.length() == 0) {
            return;
        }
        textArea.append(delta);
        refreshLayout();
    }

    public void setHeader(String header) {
        headerLabel.setText(normalize(header));
        headerLabel.setVisible(headerLabel.getText().length() > 0);
        refreshLayout();
    }

    /**
     * Colors the header label (the sender name), used for per-participant colors in Partying
     * mode.  {@code null} restores the default muted text color.
     */
    public void setHeaderColor(Color color) {
        headerLabel.setForeground(color != null ? color : withAlpha(textColor, 220));
        repaint();
    }

    public void setMaximumBubbleWidth(int maximumBubbleWidth) {
        if (maximumBubbleWidth < MINIMUM_WIDTH) {
            throw new IllegalArgumentException("maximumBubbleWidth must be at least " + MINIMUM_WIDTH);
        }
        this.maximumBubbleWidth = maximumBubbleWidth;
        refreshLayout();
    }

    @Override
    public Dimension getPreferredSize() {
        int contentMaximumWidth = maximumBubbleWidth
                - TAIL_WIDTH
                - (HORIZONTAL_PADDING * 2);
        int naturalWidth = calculateNaturalTextWidth();
        int contentWidth = Math.max(72, Math.min(contentMaximumWidth, naturalWidth));

        textArea.setSize(new Dimension(contentWidth, Short.MAX_VALUE));
        Dimension textSize = textArea.getPreferredSize();
        Dimension headerSize = headerLabel.isVisible()
                ? headerLabel.getPreferredSize()
                : new Dimension(0, 0);

        int width = Math.max(textSize.width, headerSize.width)
                + (HORIZONTAL_PADDING * 2)
                + TAIL_WIDTH;
        width = Math.max(MINIMUM_WIDTH, Math.min(maximumBubbleWidth, width));

        int height = VERTICAL_PADDING * 2 + textSize.height;
        if (headerLabel.isVisible()) {
            height += headerSize.height + 3;
        }
        return new Dimension(width, Math.max(48, height));
    }

    /**
     * Deterministic height for a fixed bubble width: paddings + optional header + the text wrapped
     * at exactly the inner width.  Uses the larger of the text view's measurement and a
     * font-metrics greedy-wrap estimate, so a stale unwrapped view measurement can never produce
     * a one-line bubble for a long text.
     */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(24, width - insets.left - insets.right);
        textArea.setSize(new Dimension(innerWidth, Short.MAX_VALUE));
        int viewHeight = textArea.getPreferredSize().height;
        int metricsHeight = estimateWrappedTextHeight(innerWidth);
        int height = insets.top + insets.bottom + Math.max(viewHeight, metricsHeight);
        if (headerLabel.isVisible()) {
            height += headerLabel.getPreferredSize().height + 3;
        }
        return Math.max(48, height);
    }

    /** Greedy word-wrap line count from font metrics — independent of the Swing view state. */
    private int estimateWrappedTextHeight(int innerWidth) {
        FontMetrics metrics = textArea.getFontMetrics(textArea.getFont());
        int lineHeight = metrics.getHeight();
        int lines = 0;
        for (String paragraph : textArea.getText().split("\n", -1)) {
            lines += countWrappedLines(paragraph, metrics, innerWidth);
        }
        return Math.max(1, lines) * lineHeight;
    }

    private static int countWrappedLines(String paragraph, FontMetrics metrics, int width) {
        if (paragraph.isEmpty()) {
            return 1;
        }
        int lines = 1;
        int currentWidth = 0;
        int spaceWidth = metrics.charWidth(' ');
        for (String word : paragraph.split(" ")) {
            int wordWidth = metrics.stringWidth(word);
            if (currentWidth > 0 && currentWidth + spaceWidth + wordWidth > width) {
                lines++;
                currentWidth = 0;
            }
            if (wordWidth > width) {
                // Overlong words wrap mid-word across additional lines.
                lines += wordWidth / Math.max(1, width);
                currentWidth = wordWidth % Math.max(1, width);
            } else {
                currentWidth += (currentWidth > 0 ? spaceWidth : 0) + wordWidth;
            }
        }
        return lines;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            applyQualityHints(copy);
            copy.setComposite(AlphaComposite.SrcOver);
            copy.setColor(bubbleColor);
            copy.fill(createBubbleShape());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private void buildUi() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 3));
        setBorder(createContentBorder());
        add(headerLabel, BorderLayout.NORTH);
        add(textArea, BorderLayout.CENTER);
    }

    private JLabel createHeaderLabel(String header) {
        JLabel label = new JLabel(normalize(header));
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(baseFont.deriveFont(Font.BOLD, Math.max(10f, baseFont.getSize2D() - 1f)));
        label.setForeground(withAlpha(textColor, 220));
        label.setOpaque(false);
        label.setVisible(label.getText().length() > 0);
        return label;
    }

    private JTextArea createTextArea(String text) {
        JTextArea area = new JTextArea(normalize(text));
        Font baseFont = UIManager.getFont("TextArea.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        area.setFont(baseFont.deriveFont(Font.PLAIN, Math.max(13f, baseFont.getSize2D())));
        area.setForeground(textColor);
        area.setCaretColor(textColor);
        area.setSelectionColor(withAlpha(textColor, 90));
        area.setSelectedTextColor(textColor);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(true);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private EmptyBorder createContentBorder() {
        int left = HORIZONTAL_PADDING + (side.pointsLeft() ? TAIL_WIDTH : 0);
        int right = HORIZONTAL_PADDING + (side.pointsRight() ? TAIL_WIDTH : 0);
        return new EmptyBorder(VERTICAL_PADDING, left, VERTICAL_PADDING, right);
    }

    private java.awt.Shape createBubbleShape() {
        Insets insets = getInsets();
        int bodyX = side.pointsLeft() ? TAIL_WIDTH : 0;
        int bodyWidth = Math.max(1, getWidth() - TAIL_WIDTH);
        int bodyHeight = Math.max(1, getHeight());
        RoundRectangle2D body = new RoundRectangle2D.Float(
                bodyX,
                0,
                bodyWidth,
                bodyHeight,
                ARC,
                ARC);

        Path2D tail = new Path2D.Float();
        int centerY = Math.max(insets.top + 13, getHeight() - 22);
        if (side.pointsRight()) {
            int baseX = getWidth() - TAIL_WIDTH - 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(getWidth() - 1, centerY);
            tail.lineTo(baseX, centerY + 8);
        } else {
            int baseX = TAIL_WIDTH + 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(1, centerY);
            tail.lineTo(baseX, centerY + 8);
        }
        tail.closePath();

        java.awt.geom.Area shape = new java.awt.geom.Area(body);
        shape.add(new java.awt.geom.Area(tail));
        return shape;
    }

    private int calculateNaturalTextWidth() {
        FontMetrics textMetrics = textArea.getFontMetrics(textArea.getFont());
        int maximum = 72;
        String[] lines = textArea.getText().split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            maximum = Math.max(maximum, textMetrics.stringWidth(lines[index]) + 8);
        }
        if (headerLabel.isVisible()) {
            FontMetrics headerMetrics = headerLabel.getFontMetrics(headerLabel.getFont());
            maximum = Math.max(maximum, headerMetrics.stringWidth(headerLabel.getText()) + 8);
        }
        return maximum;
    }

    private void refreshLayout() {
        revalidate();
        repaint();
    }

    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
