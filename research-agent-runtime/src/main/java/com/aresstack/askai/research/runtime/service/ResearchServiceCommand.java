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

    /**
     * The EXPLICIT post-search review (issue #29): the user pressed "Neue Quellen auswerten". The TeamAgent
     * reviews the accepted sources; bracketed by manual_search_review started/finished on this request id.
     */
    public static final String TYPE_REVIEW_SOURCES = "review_sources";

    /**
     * The AUTHORITATIVE scope projection for the next turn: the host owns the research scope, the model
     * reads it as context. Without it the model would reconstruct the scope from the chat history, which is
     * exactly how earlier decisions get lost.
     */
    public static final String TYPE_SET_SCOPE = "set_scope";

    private final String type;
    private final String requestId;
    private final String query;
    private final String language;
    private final String scope;

    public ResearchServiceCommand(String type, String requestId, String query) {
        this(type, requestId, query, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language) {
        this(type, requestId, query, language, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope) {
        this.type = type == null ? "" : type;
        this.requestId = requestId == null ? "" : requestId;
        this.query = query == null ? "" : query;
        this.language = language == null ? "" : language;
        this.scope = scope == null ? "" : scope;
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

    /** The rendered scope projection of a {@code set_scope} command; empty when absent. */
    public String getScope() {
        return scope;
    }
}
