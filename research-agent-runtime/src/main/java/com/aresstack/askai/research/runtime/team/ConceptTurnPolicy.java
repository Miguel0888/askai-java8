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
        DELETE_READ_ONLY,
        /**
         * An explicit MOVE order (move_leaf slice): FULL permissions, but the receipt-truth
         * guard arms — a turn without a MOVED receipt closes with the deterministic host
         * sentence instead of the model's narration ("verschoben" only when a receipt of the
         * CURRENT turn covers it; the add_cards gate saw a NONE turn claim an executed change).
         */
        MOVE_TRUTH
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
            "umbau"
    };
    // "verschieb" left the restructure blockers with the real move_leaf tool; its detection
    // lives in hasUnnegatedMoveMention (word-bounded, negation-aware).

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
        // "move" as a WORD only — "remove"/"movement" must not arm the truth guard; and a
        // NEGATED mention never arms it ("kein Verschieben" once flipped a successful add
        // turn into a false host 'nothing changed' answer).
        if (hasUnnegatedMoveMention(prompt)) {
            return Mode.MOVE_TRUTH;
        }
        return Mode.FULL;
    }

    /** Negation tokens that, right before a move verb, mark it as NOT an order. */
    private static final String[] NEGATIONS = {
            "kein", "nicht", "ohne", "statt", "anstatt", "not ", "no ", "never", "without",
            "don't", "dont"
    };

    private static boolean hasUnnegatedMoveMention(String prompt) {
        java.util.List<Integer> hits = new java.util.ArrayList<Integer>();
        int from = 0;
        while (true) {
            int index = prompt.indexOf("verschieb", from);
            if (index < 0) {
                break;
            }
            hits.add(index);
            from = index + 1;
        }
        java.util.regex.Matcher move = java.util.regex.Pattern
                .compile("(?<![a-zäöüß])move(?![a-zäöüß])").matcher(prompt);
        while (move.find()) {
            hits.add(move.start());
        }
        for (int index : hits) {
            String window = prompt.substring(Math.max(0, index - 28), index);
            // A negation only counts within the SAME clause — "Nicht löschen! Verschiebe …"
            // is an order, "… kein Verschieben." is not.
            for (char delimiter : new char[] {'.', '!', '?', ';'}) {
                int cut = window.lastIndexOf(delimiter);
                if (cut >= 0) {
                    window = window.substring(cut + 1);
                }
            }
            boolean negated = false;
            for (String negation : NEGATIONS) {
                if (window.contains(negation)) {
                    negated = true;
                    break;
                }
            }
            if (!negated) {
                return true; // ONE unnegated mention is an order
            }
        }
        return false;
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
