package com.aresstack.askai.research.runtime.team;

import java.util.Locale;

/**
 * The MACHINE-side intent separation of one user turn (safety-slice gate 2 finding: with rewrite
 * withdrawn, the model simply substituted partial adds for a compound restructuring wish — and a
 * concept DELETE wish must never silently become a scope exclusion; those are two different user
 * intents). Deliberately a dumb keyword classifier: deterministic, test-pinned, biased toward
 * safety — a restructure false positive only defers additions with an honest sentence, it never
 * destroys; the stricter DELETE mode (whose answer the host replaces entirely) therefore
 * demands verb AND structure noun.
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
         * An explicit concept DELETE wish ("lösche die Karte/den Zweig"): no concept mutation,
         * no exclude, and a TERMINAL host-deterministic answer (the model once claimed the
         * branch was deleted over an unchanged concept). Because the whole visible answer is
         * replaced, this mode demands verb AND structure noun — a bare "lösche X" stays
         * conversational, exactly like a bare "entferne X".
         */
        DELETE_READ_ONLY
    }

    private static final String[] DELETE_VERBS = {
            "lösch", "loesch", "delete", "streich", "entfern", "remove"
    };
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
        if (containsAny(prompt, DELETE_VERBS) && containsAny(prompt, STRUCTURE_NOUNS)) {
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
