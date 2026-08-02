package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The SCOPING phase's own structured model output (RA-P6 §29). Kept strictly separate from the generic
 * {@link TeamAgentTurn} so scoping-only fields never leak into the contract Outline/Evidence/Drafting must
 * carry. In this slice it is parsed, validated and made round-trippable; nothing yet persists a brief, renders
 * a map or runs a search.
 * <ul>
 *   <li>{@link #getAssistantMessage()} — the visible reply (required);</li>
 *   <li>{@link #getResearchBriefMarkdown()} — the continuously-maintained research brief (required, may be a
 *       very short first draft after a one-word topic);</li>
 *   <li>{@link #getExplorationMapMermaid()} — an idea map as bare Mermaid (no fences), may be empty;</li>
 *   <li>{@link #getSearchSuggestions()} — engine-facing queries, kept apart from the research question;</li>
 *   <li>{@link #getAdvice()} — an ADVISORY stay/continue recommendation with no workflow effect.</li>
 * </ul>
 */
public final class ScopingAssistantOutput implements PhaseAssistantOutput {

    private final String assistantMessage;
    private final String researchBriefMarkdown;
    private final String explorationMapMermaid;
    private final List<SearchSuggestion> searchSuggestions;
    private final PhaseAdvice advice;

    public ScopingAssistantOutput(String assistantMessage, String researchBriefMarkdown,
                                  String explorationMapMermaid, List<SearchSuggestion> searchSuggestions,
                                  PhaseAdvice advice) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("assistantMessage must not be blank");
        }
        if (researchBriefMarkdown == null || researchBriefMarkdown.trim().isEmpty()) {
            throw new IllegalArgumentException("researchBriefMarkdown must not be blank");
        }
        // A scoping output is the COMPLETE current snapshot of the phase (RA-P6.5): it always carries an
        // exploration map and at least one search suggestion, so a later turn can never blank a working
        // projection. Incomplete states are simply not representable.
        if (explorationMapMermaid == null || explorationMapMermaid.trim().isEmpty()) {
            throw new IllegalArgumentException("explorationMapMermaid must not be blank");
        }
        if (searchSuggestions == null || searchSuggestions.isEmpty()) {
            throw new IllegalArgumentException("searchSuggestions must not be empty");
        }
        this.assistantMessage = assistantMessage.trim();
        this.researchBriefMarkdown = researchBriefMarkdown.trim();
        this.explorationMapMermaid = explorationMapMermaid == null ? "" : explorationMapMermaid.trim();
        this.searchSuggestions = searchSuggestions == null
                ? Collections.<SearchSuggestion>emptyList()
                : Collections.unmodifiableList(new ArrayList<SearchSuggestion>(searchSuggestions));
        this.advice = advice == null ? PhaseAdvice.neutral() : advice;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public String getResearchBriefMarkdown() {
        return researchBriefMarkdown;
    }

    public String getExplorationMapMermaid() {
        return explorationMapMermaid;
    }

    public List<SearchSuggestion> getSearchSuggestions() {
        return searchSuggestions;
    }

    public PhaseAdvice getAdvice() {
        return advice;
    }

    public String canonicalJson() {
        return ScopingAssistantOutputCodec.toJson(this);
    }
}
