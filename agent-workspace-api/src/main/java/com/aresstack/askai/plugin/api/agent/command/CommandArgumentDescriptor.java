package com.aresstack.askai.plugin.api.agent.command;

/** Describes one positional argument of a slash command (for usage display and completion). */
public final class CommandArgumentDescriptor {

    private final String name;
    private final String description;
    private final boolean required;

    public CommandArgumentDescriptor(String name, String description, boolean required) {
        this.name = name == null ? "" : name.trim();
        this.description = description == null ? "" : description.trim();
        this.required = required;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }
}
