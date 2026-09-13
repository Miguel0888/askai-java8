package com.aresstack.askai.research.runtime.team;

import java.util.Locale;

/**
 * The MACHINE-side intent separation of one user turn (safety-slice gate 2 finding: with rewrite
 * withdrawn, the model simply substituted partial adds for a compound restructuring wish — and a
 * concept DELETE wish must never silently become a scope exclusion; those are two different user
 * intents). Deliberately a dumb keyword classifier: deterministic, test-pinned, biased toward
 * safety — a false positive only defers additions with an honest sentence, it never destroys.
 */
public final class ConceptTurnPolicy {

    /** What the model may do to the concept within THIS turn. */
    public enum Mode {
        /** Normal turn: read/add/rename/exclude/resolve/offer. */
        FULL,
        /**
         * A compound restructuring wish ("überarbeite/ersetze/strukturiere den Zweig"): concept
         * MUTATIONS are refused for the whole turn (no partial-add substitutes) until the
         * plan-validate-stage-commit workflow exists; reading, offering and the exclusion
         * facade stay available.
         */
        RESTRUCTURE_READ_ONLY,
        /**
         * An explicit concept DELETE wish ("lösche die Karte/den Zweig"): no concept mutation
         * AND no exclude — deleting a card is manual editor work and is NOT the same intent as
         * ruling a topic out of scope.
         */
        DELETE_READ_ONLY
    }

    private static final String[] DELETE_VERBS = {
            "lösch", "loesch", "delete", "streich"
    };
    /** "entferne/remove …" alone stays conversational; WITH a structure noun it is a delete wish. */
    private static final String[] REMOVE_VERB = {"entfern", "remove"};
    private static final String[] STRUCTURE_NOUNS = {
            "zweig", "karte", "knoten", "ast", "branch", "card", "node", "konzept", "concept"
    };
    private static final String[] RESTRUCTURE_VERBS = {
            "überarbeit", "ueberarbeit", "ersetz", "umstrukturier", "restrukturier",
            "strukturiere", "gliedere", "reorganis", "reorganiz", "rewrite", "restructur",
            "umbau", "verschieb"
    };

    private ConceptTurnPolicy() {
    }

    /** Classify one user prompt. Delete wins over restructure (it is the stricter mode). */
    public static Mode modeFor(String userPrompt) {
        String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        if (containsAny(prompt, DELETE_VERBS)
                || (containsAny(prompt, REMOVE_VERB) && containsAny(prompt, STRUCTURE_NOUNS))) {
            return Mode.DELETE_READ_ONLY;
        }
        if (containsAny(prompt, RESTRUCTURE_VERBS)) {
            return Mode.RESTRUCTURE_READ_ONLY;
        }
        return Mode.FULL;
    }

    private static boolean containsAny(String prompt, String[] needles) {
        for (String needle : needles) {
            if (prompt.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
