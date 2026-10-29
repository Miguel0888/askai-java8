package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution;

/**
 * The research agent's TITLE-BAR search: a web search. Firing runs
 * {@link ResearchAgentSession#requestManualWebSearch} — the SAME phase-independent /search path as a
 * suggestion click, so the captured sources flow into the corpus and the bot can review them afterwards.
 * Never a chat turn, never a state command. The host owns the field (look, Enter, clearing).
 */
public final class ResearchWebSearchContribution implements AgentSearchContribution {

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public String getPlaceholder() {
        return "Web search…";
    }

    @Override
    public String getTooltip() {
        return "Search the web directly (like /search)";
    }

    @Override
    public void search(AgentSession session, String query) {
        if (session instanceof ResearchAgentSession && query != null && !query.trim().isEmpty()) {
            ((ResearchAgentSession) session).requestManualWebSearch(query.trim());
        }
    }
}
