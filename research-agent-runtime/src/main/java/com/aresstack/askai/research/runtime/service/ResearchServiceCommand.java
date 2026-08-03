package com.aresstack.askai.research.runtime.service;

/**
 * A typed host→runtime SERVICE COMMAND, parsed from the {@code #RSC1#} control envelope. A service command is
 * carried over the ACP prompt frame (the only host→agent channel) but is NOT a chat turn: it is dispatched
 * before any model / TeamAgent / state logic and reaches a plain application service (e.g. the productive
 * search backend). It never transitions the host state machine and is phase-independent.
 */
public final class ResearchServiceCommand {

    /** A user-triggered web search: run the productive SearchStrategy and stream typed result events back. */
    public static final String TYPE_MANUAL_SEARCH = "manual_search";

    /**
     * A live working-language switch: only updates the runtime's {@link SessionResearchLanguage} — no model
     * call, no history entry, no state-machine command, no workflow event.
     */
    public static final String TYPE_SET_LANGUAGE = "set_language";

    private final String type;
    private final String requestId;
    private final String query;
    private final String language;

    public ResearchServiceCommand(String type, String requestId, String query) {
        this(type, requestId, query, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language) {
        this.type = type == null ? "" : type;
        this.requestId = requestId == null ? "" : requestId;
        this.query = query == null ? "" : query;
        this.language = language == null ? "" : language;
    }

    public String getType() {
        return type;
    }

    /** Correlates the command with its typed result/progress/failure events and with a later cancel. */
    public String getRequestId() {
        return requestId;
    }

    public String getQuery() {
        return query;
    }

    /** The language code ("en"/"de") of a {@code set_language} command; empty when absent. */
    public String getLanguage() {
        return language;
    }
}
