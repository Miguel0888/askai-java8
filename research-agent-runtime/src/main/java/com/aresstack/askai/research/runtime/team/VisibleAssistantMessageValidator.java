package com.aresstack.askai.research.runtime.team;

import java.util.Locale;

/**
 * A hard gate that keeps CODEC/transport concerns out of the chat. A format-repair round (see
 * {@link TeamAgentPlaybook#repairNudge()}) can provoke the model into an apology or a meta-explanation whose
 * {@code assistantMessage} — "I apologize if my previous response was not formatted correctly…" — would
 * otherwise be shown verbatim. A repaired turn whose visible message fails this check is treated as an
 * UNUSABLE_ANSWER instead of being surfaced, so a repair can never become a visible business turn.
 *
 * <p>Only genuinely meta phrases/tokens are rejected. Bare everyday words that legitimately occur in warm
 * scoping prose (e.g. "command of the subject", "formatting your report") are deliberately NOT banned to
 * avoid suppressing a real answer.</p>
 */
public final class VisibleAssistantMessageValidator {

    /** Meta phrases that only appear when the model is talking about the wire, not the research. */
    private static final String[] FORBIDDEN_PHRASES = {
            "could not be parsed", "valid json", "json object", "output format", "required format",
            "structured response", "structured research", "state machine", "type the command",
            "i apologize", "apologize if", "my previous response", "previous answer", "formatted correctly"
    };

    /** Bare tokens rare enough in a warm scoping reply that their mere presence signals codec talk. */
    private static final String[] FORBIDDEN_TOKENS = {
            "json", "schema", "protocol", "parsed", "parsing"
    };

    private VisibleAssistantMessageValidator() {
    }

    /** True when {@code assistantMessage} is a real, non-empty reply free of any codec/transport meta-talk. */
    public static boolean isCleanBusinessMessage(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            return false;
        }
        String lower = assistantMessage.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN_PHRASES) {
            if (lower.contains(phrase)) {
                return false;
            }
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token)) {
                return false;
            }
        }
        return true;
    }
}
