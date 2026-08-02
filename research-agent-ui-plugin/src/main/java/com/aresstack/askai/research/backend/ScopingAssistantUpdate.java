package com.aresstack.askai.research.backend;

import java.util.Collections;
import java.util.List;

/**
 * The UI-side decoding of the scoping assistant PROJECTION (RA-P6 §1): the exploration map (bare Mermaid), the
 * search suggestions and the advisory advice for the scoping workspace. Display-only support content — it
 * carries NO research brief (the brief has its own persistence route) and NO workflow authority. It is a
 * transient "current working state" of the panel, not a historised artifact: a later turn REPLACES it.
 */
public final class ScopingAssistantUpdate {

    private final String phaseId;
    private final List<Suggestion> searchSuggestions;
    private final String adviceRecommendation;
    private final String adviceReason;

    public ScopingAssistantUpdate(String phaseId, List<Suggestion> searchSuggestions,
                                  String adviceRecommendation, String adviceReason) {
        this.phaseId = phaseId == null ? "" : phaseId;
        this.searchSuggestions = searchSuggestions == null
                ? Collections.<Suggestion>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<Suggestion>(searchSuggestions));
        this.adviceRecommendation = adviceRecommendation == null ? "NEUTRAL" : adviceRecommendation;
        this.adviceReason = adviceReason == null ? "" : adviceReason;
    }

    public String getPhaseId() {
        return phaseId;
    }

    public List<Suggestion> getSearchSuggestions() {
        return searchSuggestions;
    }

    /** ADVISORY ONLY (STAY|CONTINUE|NEUTRAL): displayed at most, never a gate — no code branches on it. */
    public String getAdviceRecommendation() {
        return adviceRecommendation;
    }

    public String getAdviceReason() {
        return adviceReason;
    }

    /** One engine-facing search suggestion, kept distinct from the research question. */
    public static final class Suggestion {
        private final String query;
        private final String purpose;
        private final int priority;

        public Suggestion(String query, String purpose, int priority) {
            this.query = query == null ? "" : query;
            this.purpose = purpose == null ? "" : purpose;
            this.priority = priority;
        }

        public String getQuery() {
            return query;
        }

        public String getPurpose() {
            return purpose;
        }

        public int getPriority() {
            return priority;
        }
    }
}
