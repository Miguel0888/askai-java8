package com.aresstack.askai.java8.ui;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatComposerPanelTest {

    @Test
    public void switchesThePrimaryActionBetweenSendAndStop() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);

                assertFalse(composer.isSendEnabled());
                composer.setMessage("Hello");
                assertTrue(composer.isSendEnabled());

                findButton(composer, "Send").doClick();
                assertEquals(1, actions.count("send"));

                composer.setChatBusy(true);
                assertFalse(findButton(composer, "Send").isVisible());
                assertTrue(findButton(composer, "Stop").isVisible());

                findButton(composer, "Stop").doClick();
                assertEquals(1, actions.count("stop"));
            }
        });
    }

    @Test
    public void rendersRecordingAsAnAmberContextualState() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);
                composer.setMessage("Keep this draft");

                composer.setDictationView(new ChatComposerPanel.DictationView(
                        "Stop", true, true, false, true,
                        false, false, false, false, true));

                assertFalse(composer.isSendEnabled());
                JButton record = findButton(composer, "Record or stop dictation");
                JButton discard = findButton(composer, "Discard or cancel dictation");
                assertEquals("Stop", record.getText());
                assertTrue(discard.isVisible());

                record.doClick();
                discard.doClick();
                assertEquals(1, actions.count("record"));
                assertEquals(1, actions.count("discard"));
            }
        });
    }

    private static JButton findButton(Container root, String accessibleName) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton) {
                JComponent swingComponent = (JComponent) component;
                if (accessibleName.equals(swingComponent.getAccessibleContext().getAccessibleName())) {
                    return (JButton) component;
                }
            }
            if (component instanceof Container) {
                JButton match = findButtonOrNull((Container) component, accessibleName);
                if (match != null) {
                    return match;
                }
            }
        }
        throw new IllegalStateException("Missing button: " + accessibleName);
    }

    private static JButton findButtonOrNull(Container root, String accessibleName) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton) {
                JComponent swingComponent = (JComponent) component;
                if (accessibleName.equals(swingComponent.getAccessibleContext().getAccessibleName())) {
                    return (JButton) component;
                }
            }
            if (component instanceof Container) {
                JButton match = findButtonOrNull((Container) component, accessibleName);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static final class ActionCounter implements ChatComposerPanel.Actions {
        private final Map<String, Integer> counts = new HashMap<String, Integer>();

        public void selectModel() {
            increment("model");
        }

        public void send() {
            increment("send");
        }

        public void stop() {
            increment("stop");
        }

        public void toggleRecording() {
            increment("record");
        }

        public void discardDictation() {
            increment("discard");
        }

        public void retryTranscription() {
            increment("retry");
        }

        public void saveRecording() {
            increment("save");
        }

        public void installAudioModel() {
            increment("install");
        }

        public void transcribeAudioFile() {
            increment("file");
        }

        private void increment(String action) {
            counts.put(action, count(action) + 1);
        }

        private int count(String action) {
            Integer value = counts.get(action);
            return value == null ? 0 : value.intValue();
        }
    }
}
