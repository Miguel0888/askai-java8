package com.aresstack.askai.plugin.api.agent.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The suggestions a contribution offers for the current completion request. */
public final class CommandCompletionResult {

    private static final CommandCompletionResult EMPTY =
            new CommandCompletionResult(Collections.<CommandCompletion>emptyList());

    private final List<CommandCompletion> completions;

    public CommandCompletionResult(List<CommandCompletion> completions) {
        this.completions = Collections.unmodifiableList(new ArrayList<CommandCompletion>(
                completions == null ? Collections.<CommandCompletion>emptyList() : completions));
    }

    public static CommandCompletionResult empty() {
        return EMPTY;
    }

    public static CommandCompletionResult of(CommandCompletion... completions) {
        return new CommandCompletionResult(Arrays.asList(completions));
    }

    public List<CommandCompletion> getCompletions() {
        return completions;
    }
}
