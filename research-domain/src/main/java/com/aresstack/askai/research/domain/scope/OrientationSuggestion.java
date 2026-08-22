package com.aresstack.askai.research.domain.scope;

/**
 * A short lookup the assistant PROPOSES because it lacks the domain map — "Ich bin mir nicht sicher, ob
 * Ragout und Frikassee hier zwei Richtungen sind; ich könnte kurz nachsehen."
 * <p>
 * It is a typed PROPOSAL, nothing more: no search runs from it, no state changes, and it never alters the
 * scope by itself. Its results can only ever produce candidates for the user to accept. How such a lookup is
 * executed (provider, SERP pages, whether any page is opened at all) is deliberately NOT decided here — that
 * belongs to the search strategy work, so this contract does not pre-empt it.
 */
public final class OrientationSuggestion {

    private final String label;
    private final String query;
    private final String rationale;

    public OrientationSuggestion(String label, String query, String rationale) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        this.query = query.trim();
        this.label = label == null || label.trim().isEmpty() ? this.query : label.trim();
        this.rationale = rationale == null ? "" : rationale.trim();
    }

    /** What the user reads on the tag, in the session language (e.g. "Ragout und Frikassee abgrenzen"). */
    public String getLabel() {
        return label;
    }

    /** The engine-facing query — may deliberately differ in wording and language from the label. */
    public String getQuery() {
        return query;
    }

    /** WHY this lookup would help: which uncertainty it would reduce. */
    public String getRationale() {
        return rationale;
    }
}
