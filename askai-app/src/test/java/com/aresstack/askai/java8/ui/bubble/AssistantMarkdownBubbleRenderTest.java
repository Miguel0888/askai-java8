package com.aresstack.askai.java8.ui.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The assistant answer must render inside a speech bubble (Markdown body) and lay out + paint headless
 * without throwing — guards the regression where the answer lost its bubble chrome.
 */
public class AssistantMarkdownBubbleRenderTest {

    @Test
    public void assistantMarkdownAnswerBuildsAndPaintsInABubble() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                transcript.appendUserMessage("Show me a table and some code.");
                transcript.startAssistantMessage("AskAI");
                transcript.appendAssistantDelta("# Title\n\n- one\n- two\n\n```java\nint x = 1;\n```\n");
                transcript.finishAssistantMessage();

                transcript.setSize(900, 600);
                transcript.doLayout();
                BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    transcript.paint(graphics);
                } finally {
                    graphics.dispose();
                }
            }
        });
    }
}
