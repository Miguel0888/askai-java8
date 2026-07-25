package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.ui.bubble.BubblePalette;
import com.aresstack.askai.java8.ui.bubble.BubbleTranscriptPanel;

import javax.swing.JComponent;

/**
 * Scrollable, role-styled conversation transcript for the chat window.
 *
 * <p>A thin adapter over {@link BubbleTranscriptPanel}: user turns render as right-aligned speech
 * bubbles pointing to the centre, assistant turns as left-aligned bubbles, and system/info notes as
 * muted centred lines. The bubble text lives in transparent text areas so it stays selectable and
 * copyable. Streamed tokens append into the active assistant bubble.</p>
 *
 * <p>All mutating methods must be called on the Swing Event Dispatch Thread (the panel enforces this).
 * The existing callers already dispatch through {@code invokeLater}, so that contract holds.</p>
 */
final class ChatTranscript {

    private final BubbleTranscriptPanel panel;

    ChatTranscript() {
        this.panel = new BubbleTranscriptPanel(BubblePalette.windowsPhoneInspired());
    }

    JComponent getComponent() {
        return panel;
    }

    void clear() {
        panel.clear();
    }

    boolean isEmpty() {
        return panel.isEmpty();
    }

    /** Appends a finished user message. */
    void appendUser(String text) {
        panel.appendUserMessage(text);
    }

    /** Appends a muted, centered info/system line. */
    void appendInfo(String text) {
        panel.appendInfo(text);
    }

    /**
     * Starts a streaming assistant message with the given header (e.g. the model name).
     * Subsequent {@link #appendAssistantDelta(String)} calls append into this message.
     */
    void startAssistant(String header) {
        panel.startAssistantMessage(header);
    }

    void appendAssistantDelta(String delta) {
        panel.appendAssistantDelta(delta);
    }

    void finishAssistant() {
        panel.finishAssistantMessage();
    }
}
