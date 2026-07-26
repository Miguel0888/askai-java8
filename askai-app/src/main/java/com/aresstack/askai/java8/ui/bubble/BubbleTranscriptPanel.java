package com.aresstack.askai.java8.ui.bubble;

import com.aresstack.askai.java8.ui.markdown.MarkdownMessageView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host normal chat bubbles and temporary agent-activity animations in one scrollable transcript.
 *
 * <p>Call every mutating method on the Swing Event Dispatch Thread. Keep agent activities
 * temporary: finishing an activity plays the burst/result animation and removes its row.</p>
 */
public final class BubbleTranscriptPanel extends JPanel {

    private BubblePalette palette;
    private final JPanel messageList;
    private final JScrollPane scrollPane;
    private final SummaryOverlay overlay;
    private final Map<AnimatedThoughtBubblePanel, BubbleMessageRow> activityRows;
    // The streaming assistant answer renders as native Markdown (headings, lists, code, tables, links,
    // Mermaid) inside a speech bubble. Thinking/tool/user messages keep the plain speech bubble.
    private MarkdownMessageView activeAssistantView;

    public BubbleTranscriptPanel() {
        this(BubblePalette.windowsPhoneInspired());
    }

    public BubbleTranscriptPanel(BubblePalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
        this.messageList = createMessageList();
        this.scrollPane = createScrollPane(messageList);
        this.overlay = new SummaryOverlay();
        this.activityRows = new IdentityHashMap<AnimatedThoughtBubblePanel, BubbleMessageRow>();
        buildUi();
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    /**
     * Swaps the color palette. The transcript/background colors update immediately; new bubbles use the new
     * user/assistant colors. Existing bubbles keep the colors they were drawn with (a chat restart or the
     * next messages pick up the change), which keeps the swap cheap and avoids re-styling live bubbles.
     */
    public void applyPalette(BubblePalette newPalette) {
        requireEventDispatchThread();
        if (newPalette == null) {
            return;
        }
        this.palette = newPalette;
        setBackground(newPalette.getTranscriptBackground());
        messageList.setBackground(newPalette.getTranscriptBackground());
        scrollPane.getViewport().setBackground(newPalette.getTranscriptBackground());
        refreshTranscript();
    }

    public void clear() {
        requireEventDispatchThread();
        stopAllActivityAnimations();
        messageList.removeAll();
        activityRows.clear();
        activeAssistantView = null;
        refreshTranscript();
    }

    public boolean isEmpty() {
        return messageList.getComponentCount() == 0;
    }

    public SpeechBubblePanel appendUserMessage(String text) {
        requireEventDispatchThread();
        SpeechBubblePanel bubble = new SpeechBubblePanel(
                BubbleSide.RIGHT,
                palette.getUserBackground(),
                palette.getUserForeground(),
                "You",
                text);
        addBubbleRow(bubble, BubbleSide.RIGHT);
        return bubble;
    }

    /**
     * Starts a streaming assistant answer rendered as native Markdown inside a speech bubble. While
     * streaming the text re-renders debounced; a Mermaid fence stays a code block until
     * {@link #finishAssistantMessage()} turns it into a diagram.
     */
    public void startAssistantMessage(String header) {
        requireEventDispatchThread();
        finishAssistantMessage();
        activeAssistantView = new MarkdownMessageView();
        activeAssistantView.startStreaming();
        addAssistantMarkdownRow(header, activeAssistantView);
    }

    public void appendAssistantDelta(String delta) {
        requireEventDispatchThread();
        if (activeAssistantView == null) {
            startAssistantMessage("Assistant");
        }
        activeAssistantView.appendMarkdownDelta(delta);
        refreshTranscript();
    }

    public void finishAssistantMessage() {
        requireEventDispatchThread();
        if (activeAssistantView != null) {
            activeAssistantView.finishStreaming();
        }
        activeAssistantView = null;
    }

    public void appendInfo(String text) {
        requireEventDispatchThread();
        JLabel label = new JLabel(text == null ? "" : text, SwingConstants.CENTER);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            label.setFont(font.deriveFont(Font.ITALIC, Math.max(11f, font.getSize2D() - 1f)));
        }
        label.setForeground(palette.getInfoForeground());
        label.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        label.setAlignmentX(CENTER_ALIGNMENT);
        messageList.add(label);
        messageList.add(Box.createVerticalStrut(2));
        refreshTranscript();
    }

    /** Opaque handle to one amber tool-/agent-activity bubble; the panel keeps the component internally. */
    public static final class AgentActivityHandle {
        private final AgentActivityBubblePanel bubble;

        private AgentActivityHandle(AgentActivityBubblePanel bubble) {
            this.bubble = bubble;
        }
    }

    public AgentActivityHandle startAgentActivity(String title, String explanation) {
        requireEventDispatchThread();
        AgentActivityBubblePanel activity = new AgentActivityBubblePanel(BubbleSide.LEFT, palette, title, explanation);
        addThoughtBubble(activity);
        return new AgentActivityHandle(activity);
    }

    public void updateAgentActivity(AgentActivityHandle handle, String title, String explanation) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.updateActivity(title, explanation);
        refreshTranscript();
    }

    public void completeAgentActivity(AgentActivityHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.completeSuccessfully(summary, createActivityRemoval(handle.bubble));
    }

    public void failAgentActivity(AgentActivityHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.completeWithFailure(summary, createActivityRemoval(handle.bubble));
    }

    public void cancelAgentActivity(AgentActivityHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.cancel(summary, createActivityRemoval(handle.bubble));
    }

    // ------------------------------------------------------------------ assistant thinking

    /** Opaque handle to one assistant-thinking bubble; the panel keeps the Swing component internally. */
    public static final class ThinkingHandle {
        private final AssistantThinkingBubblePanel bubble;

        private ThinkingHandle(AssistantThinkingBubblePanel bubble) {
            this.bubble = bubble;
        }
    }

    /** Starts a green assistant-thinking bubble and begins its animation. */
    public ThinkingHandle startAssistantThinking(String modelName) {
        requireEventDispatchThread();
        String header = modelName == null || modelName.trim().isEmpty() ? "Thinking" : modelName.trim();
        AssistantThinkingBubblePanel bubble = new AssistantThinkingBubblePanel(BubbleSide.LEFT, palette, header, "");
        addThoughtBubble(bubble);
        return new ThinkingHandle(bubble);
    }

    /** Streams a reasoning delta into the thinking bubble. */
    public void appendAssistantThinkingDelta(ThinkingHandle handle, String delta) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.appendBodyText(delta);
        refreshTranscript();
    }

    /** Finishes thinking: the bubble bursts and the summary rises over the transcript, then the row is removed. */
    public void completeAssistantThinking(ThinkingHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.completeSuccessfully(summary, createActivityRemoval(handle.bubble));
    }

    public void cancelAssistantThinking(ThinkingHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.cancel(summary, createActivityRemoval(handle.bubble));
    }

    /**
     * Adds an animated thought bubble (activity or thinking) as a left row and wires its finished summary
     * to rise on the transcript-wide overlay instead of being clipped to its own row.
     */
    private void addThoughtBubble(final AnimatedThoughtBubblePanel bubble) {
        bubble.setSummaryFloatHandler(new AnimatedThoughtBubblePanel.SummaryFloatHandler() {
            public void floatSummary(AnimatedThoughtBubblePanel source, String text, Color accent, Font font) {
                Point anchor = SwingUtilities.convertPoint(source, source.getWidth() / 2, 0, overlay);
                overlay.floatSummary(anchor.x, anchor.y, text, accent, font);
            }
        });
        BubbleMessageRow row = addBubbleRow(bubble, BubbleSide.LEFT);
        activityRows.put(bubble, row);
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(palette.getTranscriptBackground());
        add(createLayeredContent(), BorderLayout.CENTER);
    }

    /** Stack the scrolling transcript under a transparent overlay that can rise past the row bounds. */
    private JLayeredPane createLayeredContent() {
        JLayeredPane layered = new JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return scrollPane.getPreferredSize();
            }
        };
        layered.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
        layered.add(overlay, JLayeredPane.MODAL_LAYER);
        return layered;
    }

    private JPanel createMessageList() {
        JPanel panel = new WidthTrackingList();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(palette.getTranscriptBackground());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 12, 0));
        return panel;
    }

    /**
     * The transcript body always matches the viewport width instead of driving its own from the rows, so
     * bubbles reflow (and never trigger a horizontal scrollbar) when the window is made narrower — the row
     * widths are derived from this width, so it must be led by the viewport, not by the rows.
     */
    private static final class WidthTrackingList extends JPanel implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private JScrollPane createScrollPane(JPanel content) {
        JScrollPane scroll = new JScrollPane(
                content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(palette.getTranscriptBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private BubbleMessageRow addBubbleRow(JComponent bubble, BubbleSide side) {
        BubbleMessageRow row = new BubbleMessageRow(bubble, side);
        row.setAlignmentX(LEFT_ALIGNMENT);
        messageList.add(row);
        messageList.add(Box.createVerticalStrut(2));
        refreshTranscript();
        return row;
    }

    /** Adds the assistant answer inside a left speech bubble, capped in width and reflowing on resize. */
    private void addAssistantMarkdownRow(String header, MarkdownMessageView view) {
        AssistantMarkdownBubble bubble = new AssistantMarkdownBubble(
                palette, header == null || header.length() == 0 ? "Assistant" : header, view);
        addBubbleRow(bubble, BubbleSide.LEFT);
    }

    private Runnable createActivityRemoval(final AnimatedThoughtBubblePanel activity) {
        return new Runnable() {
            public void run() {
                BubbleMessageRow row = activityRows.remove(activity);
                if (row != null) {
                    removeRowAndFollowingSpacer(row);
                    refreshTranscript();
                }
            }
        };
    }

    private void removeRowAndFollowingSpacer(BubbleMessageRow row) {
        int index = findComponentIndex(row);
        if (index < 0) {
            return;
        }
        messageList.remove(index);
        if (index < messageList.getComponentCount()) {
            messageList.remove(index);
        }
    }

    private int findComponentIndex(JComponent component) {
        for (int index = 0; index < messageList.getComponentCount(); index++) {
            if (messageList.getComponent(index) == component) {
                return index;
            }
        }
        return -1;
    }

    private void stopAllActivityAnimations() {
        for (AnimatedThoughtBubblePanel activity : activityRows.keySet()) {
            activity.stopAnimation();
        }
    }

    private void requireKnownActivity(AnimatedThoughtBubblePanel activity) {
        if (activity == null || !activityRows.containsKey(activity)) {
            throw new IllegalArgumentException("activity is not part of this transcript");
        }
    }

    private void refreshTranscript() {
        messageList.revalidate();
        messageList.repaint();
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                int maximum = scrollPane.getVerticalScrollBar().getMaximum();
                scrollPane.getVerticalScrollBar().setValue(maximum);
            }
        });
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Call transcript mutations on the Swing Event Dispatch Thread");
        }
    }

    /**
     * A transparent, click-through layer that animates a finished activity summary rising from the burst
     * position all the way to the top edge, over everything below it, then fades out.
     */
    private static final class SummaryOverlay extends JComponent {

        private static final int DURATION_MILLIS = 1500;
        private static final int TICK_MILLIS = 33;
        private static final int TOP_MARGIN = 8;

        private final Timer timer;
        private boolean active;
        private String text = "";
        private Color accent = Color.DARK_GRAY;
        private Font font;
        private int anchorX;
        private double startY;
        private long startedAt;

        private SummaryOverlay() {
            setOpaque(false);
            this.timer = new Timer(TICK_MILLIS, new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    tick();
                }
            });
        }

        /** Let clicks fall through to the transcript underneath. */
        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        void floatSummary(int anchorX, int startY, String text, Color accent, Font font) {
            this.anchorX = anchorX;
            this.startY = startY;
            this.text = text == null ? "" : text;
            this.accent = accent == null ? Color.DARK_GRAY : accent;
            this.font = font;
            this.startedAt = System.currentTimeMillis();
            this.active = true;
            setVisible(true);
            timer.restart();
            repaint();
        }

        private void tick() {
            if (!active || System.currentTimeMillis() - startedAt >= DURATION_MILLIS) {
                active = false;
                timer.stop();
                setVisible(false);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!active) {
                return;
            }
            double progress = Math.min(1.0d, (System.currentTimeMillis() - startedAt) / (double) DURATION_MILLIS);
            double eased = 1.0d - Math.pow(1.0d - progress, 3.0d);
            int y = (int) Math.round(startY + ((TOP_MARGIN - startY) * eased));
            float alpha = progress < 0.6d
                    ? 1.0f
                    : (float) Math.max(0.0d, 1.0d - ((progress - 0.6d) / 0.4d));

            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                copy.setComposite(AlphaComposite.SrcOver.derive(alpha));
                if (font != null) {
                    copy.setFont(font);
                }
                copy.setColor(accent);
                paintRisingText(copy, y);
            } finally {
                copy.dispose();
            }
        }

        private void paintRisingText(Graphics2D graphics, int topY) {
            FontMetrics metrics = graphics.getFontMetrics();
            int maximumWidth = Math.max(120, getWidth() - 24);
            List<String> lines = wrap(text, metrics, maximumWidth);
            int lineHeight = metrics.getHeight();
            int y = topY + metrics.getAscent();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                int width = metrics.stringWidth(line);
                int x = anchorX - (width / 2);
                x = Math.max(8, Math.min(getWidth() - width - 8, x));
                graphics.drawString(line, x, y);
                y += lineHeight;
            }
        }

        private static List<String> wrap(String text, FontMetrics metrics, int maximumWidth) {
            List<String> lines = new ArrayList<String>();
            String[] words = (text == null ? "" : text).trim().split("\\s+");
            StringBuilder current = new StringBuilder();
            for (int index = 0; index < words.length; index++) {
                String candidate = current.length() == 0 ? words[index] : current + " " + words[index];
                if (current.length() > 0 && metrics.stringWidth(candidate) > maximumWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(words[index]);
                } else {
                    current.setLength(0);
                    current.append(candidate);
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
            if (lines.isEmpty()) {
                lines.add("");
            }
            return lines;
        }
    }
}
