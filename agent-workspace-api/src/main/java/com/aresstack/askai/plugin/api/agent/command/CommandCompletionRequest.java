package com.aresstack.askai.plugin.api.agent.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A request to complete a partially-typed command. Carries the resolved command name (if the user has already
 * typed a full command word), the already-entered arguments, and the fragment currently being typed. For the
 * command-name stage the name is empty and {@link #getPartialToken()} holds what follows the {@code /}.
 */
public final class CommandCompletionRequest {

    private final String commandName;
    private final List<String> arguments;
    private final String partialToken;

    public CommandCompletionRequest(String commandName, List<String> arguments, String partialToken) {
        this.commandName = commandName == null ? "" : commandName.trim();
        this.arguments = Collections.unmodifiableList(new ArrayList<String>(
                arguments == null ? Collections.<String>emptyList() : arguments));
        this.partialToken = partialToken == null ? "" : partialToken;
    }

    public String getCommandName() {
        return commandName;
    }

    public List<String> getArguments() {
        return arguments;
    }

    /** The fragment being typed (a command name after {@code /}, or the current argument value). */
    public String getPartialToken() {
        return partialToken;
    }

    /** Zero-based index of the argument currently being completed. */
    public int getArgumentIndex() {
        return arguments.size();
    }
}
