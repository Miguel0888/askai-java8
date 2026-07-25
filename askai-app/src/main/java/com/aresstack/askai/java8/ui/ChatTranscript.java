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

    // ------------------------------------------------------------------ agent activity

    /**
     * Starts a temporary agent-activity ("thought") bubble showing a visible activity summary — not
     * internal chain-of-thought. Keep the returned handle to update or finish exactly this activity.
     */
    BubbleTranscriptPanel.AgentActivityHandle startAgentActivity(String title, String explanation) {
        return panel.startAgentActivity(title, explanation);
    }

    void updateAgentActivity(BubbleTranscriptPanel.AgentActivityHandle handle, String title, String explanation) {
        panel.updateAgentActivity(handle, title, explanation);
    }

    /** Plays the burst + rising-summary animation, then removes the activity row. */
    void completeAgentActivity(BubbleTranscriptPanel.AgentActivityHandle handle, String summary) {
        panel.completeAgentActivity(handle, summary);
    }

    // ------------------------------------------------------------------ assistant thinking

    BubbleTranscriptPanel.ThinkingHandle startAssistantThinking(String modelName) {
        return panel.startAssistantThinking(modelName);
    }

    void appendAssistantThinkingDelta(BubbleTranscriptPanel.ThinkingHandle handle, String delta) {
        panel.appendAssistantThinkingDelta(handle, delta);
    }

    void completeAssistantThinking(BubbleTranscriptPanel.ThinkingHandle handle, String summary) {
        panel.completeAssistantThinking(handle, summary);
    }

    void cancelAssistantThinking(BubbleTranscriptPanel.ThinkingHandle handle, String summary) {
        panel.cancelAssistantThinking(handle, summary);
    }

    void failAgentActivity(BubbleTranscriptPanel.AgentActivityHandle handle, String summary) {
        panel.failAgentActivity(handle, summary);
    }

    void cancelAgentActivity(BubbleTranscriptPanel.AgentActivityHandle handle, String summary) {
        panel.cancelAgentActivity(handle, summary);
    }
}
