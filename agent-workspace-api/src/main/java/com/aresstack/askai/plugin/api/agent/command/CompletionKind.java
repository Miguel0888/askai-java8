package com.aresstack.askai.plugin.api.agent.command;

/** What a {@link CommandCompletion} suggests, so the UI can style/icon it. */
public enum CompletionKind {

    /** A command name (offered right after {@code /}). */
    COMMAND,

    /** A value for a command argument (e.g. an artifact id or a section title). */
    ARGUMENT_VALUE
}
