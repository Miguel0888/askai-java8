package com.aresstack.askai.java8.ui.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BubbleComponentsTest {

    @Test
    public void pointsEveryBubbleTowardTranscriptCenter() {
        assertTrue(BubbleSide.LEFT.pointsRight());
        assertFalse(BubbleSide.LEFT.pointsLeft());
        assertTrue(BubbleSide.RIGHT.pointsLeft());
        assertFalse(BubbleSide.RIGHT.pointsRight());
    }

    @Test
    public void appendsStreamingTextWithoutReplacingExistingContent() throws Exception {
        final AtomicReference<SpeechBubblePanel> bubbleReference = new AtomicReference<SpeechBubblePanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                SpeechBubblePanel bubble = new SpeechBubblePanel(
                        BubbleSide.LEFT,
                        Color.BLUE,
                        Color.WHITE,
                        "Assistant",
                        "Hello");
                bubble.appendText(" world");
                bubbleReference.set(bubble);
            }
        });

        assertEquals("Hello world", bubbleReference.get().getText());
    }

    @Test
    public void createsAndUpdatesAgentActivityOnEventDispatchThread() throws Exception {
        final AtomicReference<AgentActivityBubblePanel> activityReference =
                new AtomicReference<AgentActivityBubblePanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                AgentActivityBubblePanel activity = transcript.startAgentActivity(
                        "Open website",
                        "Verify the official product data.");
                transcript.updateAgentActivity(
                        activity,
                        "Read website",
                        "Compare the documented capabilities.");
                activityReference.set(activity);
                transcript.clear();
            }
        });

        AgentActivityBubblePanel activity = activityReference.get();
        assertEquals("Read website", activity.getTitle());
        assertEquals("Compare the documented capabilities.", activity.getExplanation());
        assertEquals(AgentActivityBubblePanel.VisualState.RUNNING, activity.getVisualState());
        assertFalse(activity.isAnimationRunning());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsTranscriptMutationOutsideEventDispatchThread() {
        new BubbleTranscriptPanel().appendUserMessage("Hello");
    }
}
