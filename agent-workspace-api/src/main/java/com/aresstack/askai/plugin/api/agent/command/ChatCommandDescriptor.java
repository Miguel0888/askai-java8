package com.aresstack.askai.plugin.api.agent.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Metadata for a slash command: its name (without the leading {@code /}), a short description, a usage string
 * and its argument descriptors. Used to render the completion popup and validate invocations.
 */
public final class ChatCommandDescriptor {

    private final String name;
    private final String description;
    private final String usage;
    private final List<CommandArgumentDescriptor> arguments;

    public ChatCommandDescriptor(String name, String description, String usage,
                                 List<CommandArgumentDescriptor> arguments) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("command name must not be empty");
        }
        this.name = name.trim();
        this.description = description == null ? "" : description.trim();
        this.usage = usage == null || usage.trim().isEmpty() ? "/" + this.name : usage.trim();
        this.arguments = Collections.unmodifiableList(new ArrayList<CommandArgumentDescriptor>(
                arguments == null ? Collections.<CommandArgumentDescriptor>emptyList() : arguments));
    }

    public static ChatCommandDescriptor of(String name, String description) {
        return new ChatCommandDescriptor(name, description, null,
                Collections.<CommandArgumentDescriptor>emptyList());
    }

    public static ChatCommandDescriptor of(String name, String description, String usage,
                                           CommandArgumentDescriptor... arguments) {
        return new ChatCommandDescriptor(name, description, usage, Arrays.asList(arguments));
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getUsage() {
        return usage;
    }

    public List<CommandArgumentDescriptor> getArguments() {
        return arguments;
    }
}
