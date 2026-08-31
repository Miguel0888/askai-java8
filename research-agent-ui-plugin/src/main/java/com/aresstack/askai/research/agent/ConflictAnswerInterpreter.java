package com.aresstack.askai.research.agent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The HOST'S reading of the user's answer to an open concept-conflict question (gate 6 contract:
 * while a conflict is pending, the host OWNS the next unambiguous yes/no — the model neither
 * decides it nor claims its execution). Deliberately dumb and deterministic: exact token match on
 * a SHORT answer; anything longer or unlisted is conversation and goes to the model, which the
 * fence's OPEN CONCEPT CONFLICT block still guides.
 */
final class ConflictAnswerInterpreter {

    private static final int MAX_DECISION_LENGTH = 40;

    private static final Set<String> YES = new HashSet<String>(Arrays.asList(
            "ja", "jawohl", "jep", "jo", "jup", "yep", "yes", "yup", "klar", "gerne", "ok",
            "okay", "bitte", "ja bitte", "ja gerne", "ja klar", "mach das", "mach es",
            "entfernen", "entferne ihn", "entferne es", "ja entfernen", "bitte entfernen",
            "aus dem konzept entfernen", "remove", "remove it", "yes please", "sure", "do it"));

    private static final Set<String> NO = new HashSet<String>(Arrays.asList(
            "nein", "no", "nö", "nee", "nope", "nein danke", "lass es", "lass ihn stehen",
            "lass es stehen", "lass ihn drin", "stehen lassen", "behalten", "drin lassen",
            "nicht entfernen", "nein stehen lassen", "keep", "keep it", "leave it",
            "unterdrückt lassen", "so lassen", "lasse es so"));

    private ConflictAnswerInterpreter() {
    }

    /** {@code TRUE} = remove, {@code FALSE} = keep suppressed, {@code null} = not a decision. */
    static Boolean interpret(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        // Trailing punctuation and inner commas never change a decision ("Ja.", "Nein, danke").
        normalized = normalized.replaceAll("[!.?…]+$", "").replace(",", "").trim();
        normalized = normalized.replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > MAX_DECISION_LENGTH) {
            return null;
        }
        if (YES.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (NO.contains(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
