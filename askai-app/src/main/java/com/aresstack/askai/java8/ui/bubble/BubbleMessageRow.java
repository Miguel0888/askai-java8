package com.aresstack.askai.java8.ui.bubble;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Insets;

/** Align one bubble at its participant side and keep the middle-facing edge free. */
final class BubbleMessageRow extends JPanel {

    private static final int HORIZONTAL_MARGIN = 14;
    private static final int VERTICAL_GAP = 5;
    /**
     * How much of the row a bubble may fill. Only a MODERATE margin is left towards the opposite side:
     * enough that left and right bubbles still read as a conversation, but not so much that long messages
     * are squeezed into a narrow column while the window is wide.
     */
    private static final double MAXIMUM_WIDTH_RATIO = 0.92d;

    private final JComponent bubble;
    private final BubbleSide side;

    BubbleMessageRow(JComponent bubble, BubbleSide side) {
        if (bubble == null) {
            throw new IllegalArgumentException("bubble must not be null");
        }
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        this.bubble = bubble;
        this.side = side;
        setOpaque(false);
        setLayout(null);
        add(bubble);
    }

    JComponent getBubble() {
        return bubble;
    }

    @Override
    public Dimension getPreferredSize() {
        int availableWidth = resolveAvailableWidth();
        int bubbleWidth = calculateBubbleWidth(availableWidth);
        Dimension bubbleSize = getBubblePreferredSize(bubbleWidth);
        return new Dimension(Math.max(1, availableWidth), bubbleSize.height + (VERTICAL_GAP * 2));
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }

    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int availableWidth = Math.max(1, getWidth() - insets.left - insets.right);
        int bubbleWidth = calculateBubbleWidth(availableWidth);
        Dimension bubbleSize = getBubblePreferredSize(bubbleWidth);
        int x = side == BubbleSide.LEFT
                ? insets.left + HORIZONTAL_MARGIN
                : getWidth() - insets.right - HORIZONTAL_MARGIN - bubbleWidth;
        int y = insets.top + VERTICAL_GAP;
        bubble.setBounds(x, y, bubbleWidth, bubbleSize.height);
    }

    /**
     * The bubble width is derived from the width the chat HAS, not from a pixel constant inside the bubble:
     * a fixed cap either does nothing on a narrow window or leaves large empty margins on a wide one. The
     * row therefore hands its share down and lets the bubble ask for at most that.
     */
    private int calculateBubbleWidth(int availableWidth) {
        int maximumAllowed = Math.max(120,
                (int) Math.floor((availableWidth - (HORIZONTAL_MARGIN * 2)) * MAXIMUM_WIDTH_RATIO));
        int preferredWidth = bubble instanceof WidthBoundedBubble
                ? ((WidthBoundedBubble) bubble).preferredWidthWithin(maximumAllowed)
                : bubble.getPreferredSize().width;
        return Math.max(100, Math.min(maximumAllowed, preferredWidth));
    }

    private Dimension getBubblePreferredSize(int bubbleWidth) {
        if (bubble instanceof com.aresstack.askai.java8.ui.markdown.WidthAwareHeight) {
            // Deterministic: ask for the height at exactly this width, no size-then-hope-for-a-second-pass.
            int height = ((com.aresstack.askai.java8.ui.markdown.WidthAwareHeight) bubble)
                    .preferredHeightForWidth(bubbleWidth);
            return new Dimension(bubbleWidth, height);
        }
        bubble.setSize(new Dimension(bubbleWidth, Short.MAX_VALUE));
        Dimension preferred = bubble.getPreferredSize();
        return new Dimension(bubbleWidth, preferred.height);
    }

    /**
     * The width the row will ACTUALLY be laid out at — its own width whenever it has one.
     * <p>
     * Using the parent's width instead (as this did) reports a height measured at a DIFFERENT width than
     * {@link #doLayout()} then uses. As soon as the two differ — a vertical scrollbar appearing is enough —
     * the text wraps into more lines than the reported height allows, and the last lines of a long message
     * are cut off at the bubble's bottom edge. Height and layout must be derived from one and the same
     * width.
     */
    private int resolveAvailableWidth() {
        if (getWidth() > 0) {
            return getWidth();
        }
        if (getParent() != null && getParent().getWidth() > 0) {
            return getParent().getWidth();
        }
        return 720;
    }
}
