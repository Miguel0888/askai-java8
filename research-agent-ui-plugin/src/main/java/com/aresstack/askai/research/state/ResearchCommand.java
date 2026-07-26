package com.aresstack.askai.research.state;

/**
 * An immutable command with a stable {@code commandId}. The id lets a backend treat a duplicate command
 * idempotently later; the state machine itself is pure, but the id is carried from the start so idempotency
 * can be layered on without changing the model.
 */
public final class ResearchCommand {

    private final ResearchCommandType type;
    private final String commandId;

    public ResearchCommand(ResearchCommandType type, String commandId) {
        if (type == null) {
            throw new IllegalArgumentException("command type must not be null");
        }
        if (commandId == null || commandId.trim().isEmpty()) {
            throw new IllegalArgumentException("commandId must not be empty");
        }
        this.type = type;
        this.commandId = commandId;
    }

    public static ResearchCommand of(ResearchCommandType type, String commandId) {
        return new ResearchCommand(type, commandId);
    }

    public ResearchCommandType getType() {
        return type;
    }

    public String getCommandId() {
        return commandId;
    }

    @Override
    public String toString() {
        return type + "(" + commandId + ")";
    }
}
