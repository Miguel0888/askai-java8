package com.aresstack.askai.java8.speech;

/**
 * A successful dictation: the recognized transcript (to be inserted at the caret, never auto-sent) and
 * the technical diagnostics for the details log.
 */
public final class DictationResult {

    private final String text;
    private final DictationDiagnostics diagnostics;

    public DictationResult(String text, DictationDiagnostics diagnostics) {
        this.text = text == null ? "" : text;
        this.diagnostics = diagnostics;
    }

    public String getText() {
        return text;
    }

    public DictationDiagnostics getDiagnostics() {
        return diagnostics;
    }
}
