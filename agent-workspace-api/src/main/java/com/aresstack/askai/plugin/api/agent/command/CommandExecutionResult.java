package com.aresstack.askai.plugin.api.agent.command;

/**
 * Outcome of executing a slash command. {@code HANDLED} means the contribution acted on it; {@code REJECTED}
 * means it was understood but not allowed right now (e.g. {@code /approve} with no pending gate); {@code
 * UNKNOWN} means this contribution does not handle the command. A human-readable message may accompany any
 * outcome for display in the chat.
 */
public final class CommandExecutionResult {

    public enum Status {
        HANDLED,
        REJECTED,
        UNKNOWN
    }

    private final Status status;
    private final String message;

    private CommandExecutionResult(Status status, String message) {
        this.status = status;
        this.message = message == null ? "" : message;
    }

    public static CommandExecutionResult handled() {
        return new CommandExecutionResult(Status.HANDLED, "");
    }

    public static CommandExecutionResult handled(String message) {
        return new CommandExecutionResult(Status.HANDLED, message);
    }

    public static CommandExecutionResult rejected(String message) {
        return new CommandExecutionResult(Status.REJECTED, message);
    }

    public static CommandExecutionResult unknown() {
        return new CommandExecutionResult(Status.UNKNOWN, "");
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
