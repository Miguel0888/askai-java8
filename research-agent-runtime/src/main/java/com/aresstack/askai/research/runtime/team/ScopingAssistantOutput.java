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
 *   <li>{@link #getResearchBriefMarkdown()} — the continuously-maintained research brief, the PRIMARY
 *       artifact of this phase (required, may be a very short first draft after a one-word topic);</li>
 *   <li>{@link #getSearchSuggestions()} — engine-facing queries, kept apart from the research question;</li>
 *   <li>{@link #getAdvice()} — an ADVISORY stay/continue recommendation with no workflow effect.</li>
 * </ul>
 *
 * <p>The scoping agent works on the research brief and search suggestions — NOT on visualization. Any
 * exploration-map/diagram is the job of a separate artifact visualizer, so a model that cannot draw can never
 * ruin a scoping turn.</p>
 */
public final class ScopingAssistantOutput implements PhaseAssistantOutput {

    private final String assistantMessage;
    private final String researchBriefMarkdown;
    private final List<SearchSuggestion> searchSuggestions;
    private final PhaseAdvice advice;

    public ScopingAssistantOutput(String assistantMessage, String researchBriefMarkdown,
                                  List<SearchSuggestion> searchSuggestions, PhaseAdvice advice) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("assistantMessage must not be blank");
        }
        if (researchBriefMarkdown == null || researchBriefMarkdown.trim().isEmpty()) {
            throw new IllegalArgumentException("researchBriefMarkdown must not be blank");
        }
        // A substantive scoping turn still carries at least one search suggestion so the user can search
        // immediately; the research brief is the phase's primary artifact.
        if (searchSuggestions == null || searchSuggestions.isEmpty()) {
            throw new IllegalArgumentException("searchSuggestions must not be empty");
        }
        this.assistantMessage = assistantMessage.trim();
        this.researchBriefMarkdown = researchBriefMarkdown.trim();
        this.searchSuggestions = Collections.unmodifiableList(
                new ArrayList<SearchSuggestion>(searchSuggestions));
        this.advice = advice == null ? PhaseAdvice.neutral() : advice;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public String getResearchBriefMarkdown() {
        return researchBriefMarkdown;
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
