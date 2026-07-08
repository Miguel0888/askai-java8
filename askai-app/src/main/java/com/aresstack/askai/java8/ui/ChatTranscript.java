package com.aresstack.askai.java8.ui;

import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;

/**
 * Scrollable, role-styled conversation transcript for the chat window.
 *
 * <p>Backed by a {@link JTextPane} so streamed tokens can be appended cheaply, text
 * stays selectable/copyable, and long messages wrap automatically. User turns are
 * right-aligned in blue, assistant turns left-aligned in green, system/info notes are
 * muted and centered.</p>
 */
final class ChatTranscript {

    private static final Color USER_COLOR = new Color(0x1565C0);
    private static final Color ASSISTANT_COLOR = new Color(0x2E7D32);
    private static final Color INFO_COLOR = new Color(0x757575);

    private final JTextPane pane;
    private final JScrollPane scrollPane;

    ChatTranscript() {
        this.pane = new JTextPane();
        this.pane.setEditable(false);
        this.pane.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 12, 8, 12));
        this.scrollPane = new JScrollPane(pane);
        this.scrollPane.getVerticalScrollBar().setUnitIncrement(16);
    }

    JScrollPane getComponent() {
        return scrollPane;
    }

    void clear() {
        pane.setText("");
    }

    boolean isEmpty() {
        return pane.getDocument().getLength() == 0;
    }

    /** Appends a finished user message. */
    void appendUser(String text) {
        appendHeader("You", USER_COLOR, StyleConstants.ALIGN_RIGHT);
        appendBody(text + "\n\n", USER_COLOR, StyleConstants.ALIGN_RIGHT);
    }

    /** Appends a muted, centered info/system line. */
    void appendInfo(String text) {
        appendBody(text + "\n\n", INFO_COLOR, StyleConstants.ALIGN_CENTER, true);
    }

    /**
     * Starts a streaming assistant message with the given header (e.g. the model name).
     * Subsequent {@link #appendAssistantDelta(String)} calls append into this message.
     */
    void startAssistant(String header) {
        appendHeader(header, ASSISTANT_COLOR, StyleConstants.ALIGN_LEFT);
    }

    void appendAssistantDelta(String delta) {
        appendBody(delta, null, StyleConstants.ALIGN_LEFT);
    }

    void finishAssistant() {
        appendBody("\n\n", null, StyleConstants.ALIGN_LEFT);
    }

    private void appendHeader(String text, Color color, int alignment) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setBold(attrs, true);
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setFontSize(attrs, 11);
        insert(text + "\n", attrs, alignment);
    }

    private void appendBody(String text, Color color, int alignment) {
        appendBody(text, color, alignment, false);
    }

    private void appendBody(String text, Color color, int alignment, boolean italic) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        if (color != null) {
            StyleConstants.setForeground(attrs, color);
        }
        StyleConstants.setItalic(attrs, italic);
        StyleConstants.setFontSize(attrs, 13);
        StyleConstants.setFontFamily(attrs, Font.SANS_SERIF);
        insert(text, attrs, alignment);
    }

    private void insert(String text, SimpleAttributeSet attrs, int alignment) {
        StyledDocument doc = pane.getStyledDocument();
        int start = doc.getLength();
        try {
            doc.insertString(start, text, attrs);
        } catch (BadLocationException ex) {
            return;
        }
        SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setAlignment(paragraph, alignment);
        StyleConstants.setSpaceBelow(paragraph, 2f);
        doc.setParagraphAttributes(start, text.length(), paragraph, false);
        pane.setCaretPosition(doc.getLength());
    }
}
