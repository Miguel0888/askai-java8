package com.aresstack.askai.research.agent;

/**
 * The scoping search-query field is the USER's local draft, kept strictly apart from the agent's projection
 * (RA-P6 §4). The agent's suggestions may prefill the field ONLY while the user has not taken it over; once the
 * user types a real query (or clicks a suggestion), a later projection must never overwrite it — so the field
 * never "jumps back" to an agent value mid-editing. Pure and UI-free so this ownership rule is unit-tested.
 */
public final class ScopingQueryDraft {

    private String text = "";
    private boolean userOwned;

    public String text() {
        return text;
    }

    /** True once the user owns the field (typed a non-empty query or clicked a suggestion). */
    public boolean isUserOwned() {
        return userOwned;
    }

    /**
     * The user edited the field. A non-empty value takes ownership (projections stop prefilling); clearing it
     * releases ownership so the next projection may prefill again.
     */
    public void userTyped(String newText) {
        this.text = newText == null ? "" : newText;
        this.userOwned = !this.text.trim().isEmpty();
    }

    /** The user explicitly clicked a suggestion: it becomes the draft and the user now owns the field. */
    public void chooseSuggestion(String query) {
        this.text = query == null ? "" : query;
        this.userOwned = true;
    }

    /**
     * A new projection arrived. Adopt {@code bestSuggestion} into the field ONLY if the user has not taken it
     * over. Returns true when the field text changed (so the caller syncs the widget), false when the user's
     * draft is protected.
     */
    public boolean adoptFromProjectionIfUnowned(String bestSuggestion) {
        if (userOwned) {
            return false;
        }
        String next = bestSuggestion == null ? "" : bestSuggestion;
        if (next.equals(text)) {
            return false;
        }
        this.text = next;
        return true;
    }
}
