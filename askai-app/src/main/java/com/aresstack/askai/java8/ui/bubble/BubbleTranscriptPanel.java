package com.aresstack.askai.java8.ui.bubble;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Host normal chat bubbles and temporary agent-activity animations in one scrollable transcript.
 *
 * <p>Call every mutating method on the Swing Event Dispatch Thread. Keep agent activities
 * temporary: finishing an activity plays the burst/result animation and removes its row.</p>
 */
public final class BubbleTranscriptPanel extends JPanel {

    private final BubblePalette palette;
    private final JPanel messageList;
    private final JScrollPane scrollPane;
    private final Map<AgentActivityBubblePanel, BubbleMessageRow> activityRows;
    private SpeechBubblePanel activeAssistantMessage;

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
        this.activityRows = new IdentityHashMap<AgentActivityBubblePanel, BubbleMessageRow>();
        buildUi();
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public void clear() {
        requireEventDispatchThread();
        stopAllActivityAnimations();
        messageList.removeAll();
        activityRows.clear();
        activeAssistantMessage = null;
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

    public SpeechBubblePanel startAssistantMessage(String header) {
        requireEventDispatchThread();
        finishAssistantMessage();
        activeAssistantMessage = new SpeechBubblePanel(
                BubbleSide.LEFT,
                palette.getAssistantBackground(),
                palette.getAssistantForeground(),
                header,
                "");
        addBubbleRow(activeAssistantMessage, BubbleSide.LEFT);
        return activeAssistantMessage;
    }

    public void appendAssistantDelta(String delta) {
        requireEventDispatchThread();
        if (activeAssistantMessage == null) {
            startAssistantMessage("Assistant");
        }
        activeAssistantMessage.appendText(delta);
        refreshTranscript();
    }

    public void finishAssistantMessage() {
        requireEventDispatchThread();
        activeAssistantMessage = null;
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

    public AgentActivityBubblePanel startAgentActivity(String title, String explanation) {
        requireEventDispatchThread();
        final AgentActivityBubblePanel activity = new AgentActivityBubblePanel(
                BubbleSide.LEFT,
                palette,
                title,
                explanation);
        BubbleMessageRow row = addBubbleRow(activity, BubbleSide.LEFT);
        activityRows.put(activity, row);
        return activity;
    }

    public void updateAgentActivity(AgentActivityBubblePanel activity,
                                    String title,
                                    String explanation) {
        requireEventDispatchThread();
        requireKnownActivity(activity);
        activity.updateActivity(title, explanation);
        refreshTranscript();
    }

    public void completeAgentActivity(final AgentActivityBubblePanel activity, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(activity);
        activity.completeSuccessfully(summary, createActivityRemoval(activity));
    }

    public void failAgentActivity(final AgentActivityBubblePanel activity, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(activity);
        activity.completeWithFailure(summary, createActivityRemoval(activity));
    }

    public void cancelAgentActivity(final AgentActivityBubblePanel activity, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(activity);
        activity.cancel(summary, createActivityRemoval(activity));
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(palette.getTranscriptBackground());
        add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel createMessageList() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(palette.getTranscriptBackground());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 12, 0));
        return panel;
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

    private Runnable createActivityRemoval(final AgentActivityBubblePanel activity) {
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
        for (AgentActivityBubblePanel activity : activityRows.keySet()) {
            activity.stopAnimation();
        }
    }

    private void requireKnownActivity(AgentActivityBubblePanel activity) {
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
}
