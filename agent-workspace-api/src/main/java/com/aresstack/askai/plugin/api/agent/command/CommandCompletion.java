package com.aresstack.askai.plugin.api.agent.command;

/** One completion suggestion: what to insert, what to show, a description, and its kind. */
public final class CommandCompletion {

    private final String insertionText;
    private final String displayText;
    private final String description;
    private final CompletionKind kind;

    public CommandCompletion(String insertionText, String displayText, String description,
                             CompletionKind kind) {
        this.insertionText = insertionText == null ? "" : insertionText;
        this.displayText = displayText == null || displayText.isEmpty() ? this.insertionText : displayText;
        this.description = description == null ? "" : description;
        this.kind = kind == null ? CompletionKind.ARGUMENT_VALUE : kind;
    }

    public String getInsertionText() {
        return insertionText;
    }

    public String getDisplayText() {
        return displayText;
    }

    public String getDescription() {
        return description;
    }

    public CompletionKind getKind() {
        return kind;
    }
}
