package com.aresstack.askai.research.agent.narration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic guard between the narrator and the chat: cheap string checks, no model, no I/O. It is the
 * INVARIANT prompt iterations are tested against — a violation never reaches the user, it triggers one
 * retry and then the static fallback (decided by the coordinator, not here). Warmth is the bonus; the
 * facts are the contract.
 */
public final class NarrationValidator {

    /** Ends-a-sentence heuristic: terminal punctuation followed by whitespace or end of text. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](\\s|$)");
    /** Internal identifiers never appear in user text: underscore ids and ALL_CAPS enum names. */
    private static final Pattern INTERNAL_ID = Pattern.compile("\\b[a-z]+_[a-z_]+\\b|\\b[A-Z]{2,}(?:_[A-Z]+)+\\b");

    public static final class Result {
        private final List<String> violations;

        private Result(List<String> violations) {
            this.violations = violations;
        }

        public boolean isValid() {
            return violations.isEmpty();
        }

        public List<String> getViolations() {
            return violations;
        }

        /** One line for the retry prompt ("fix exactly this"). */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (String violation : violations) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(violation);
            }
            return sb.toString();
        }
    }

    public Result validate(String candidate, NarrationPayload payload) {
        List<String> violations = new ArrayList<String>();
        String text = candidate == null ? "" : candidate.trim();
        if (text.isEmpty()) {
            violations.add("empty narration");
            return new Result(violations);
        }
        for (String value : payload.getData().values()) {
            if (value != null && !value.trim().isEmpty() && !text.contains(value)) {
                violations.add("missing verbatim data: \"" + value + "\"");
            }
        }
        int sentences = countSentences(text);
        if (sentences > payload.getMaxSentences()) {
            violations.add("too long: " + sentences + " sentences, allowed " + payload.getMaxSentences());
        }
        if (payload.getExpectedDecision() != null && !text.endsWith("?")) {
            violations.add("a decision is pending but the text does not end with a question");
        }
        Matcher internal = INTERNAL_ID.matcher(text);
        if (internal.find()) {
            violations.add("internal identifier leaked: \"" + internal.group() + "\"");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("lösch") || lower.contains("delete")) {
            violations.add("promises deletion — the agent never deletes after approval");
        }
        return new Result(violations);
    }

    private static int countSentences(String text) {
        Matcher matcher = SENTENCE_END.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return Math.max(count, 1);
    }
}
