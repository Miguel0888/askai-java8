package com.aresstack.askai.plugin.api.agent.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed slash-command invocation: the resolved command name and its already-tokenized arguments, plus the
 * original raw text for reference. The state machine never sees the raw string — a contribution translates
 * this into a typed domain command.
 */
public final class CommandInvocation {

    private final String name;
    private final List<String> arguments;
    private final String rawText;

    public CommandInvocation(String name, List<String> arguments, String rawText) {
        this.name = name == null ? "" : name.trim();
        this.arguments = Collections.unmodifiableList(new ArrayList<String>(
                arguments == null ? Collections.<String>emptyList() : arguments));
        this.rawText = rawText == null ? "" : rawText;
    }

    public String getName() {
        return name;
    }

    public List<String> getArguments() {
        return arguments;
    }

    /** @return the argument at {@code index}, or {@code ""} if absent. */
    public String getArgument(int index) {
        return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
    }

    public String getRawText() {
        return rawText;
    }
}
