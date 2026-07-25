package com.aresstack.askai.java8.speech;

/**
 * A structured dictation failure: the {@link DictationErrorKind}, a technical detail message (safe to
 * log), and whether a temporary recording remains available for Retry/Save. The Swing layer turns this
 * into a friendly message and the matching recovery actions.
 */
public final class DictationFailure {

    private final DictationErrorKind kind;
    private final String detail;

    public DictationFailure(DictationErrorKind kind, String detail) {
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    public DictationErrorKind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    /** @return whether a temporary recording is kept for Retry/Save after this failure. */
    public boolean keepsRecording() {
        return kind.keepRecording();
    }
}
