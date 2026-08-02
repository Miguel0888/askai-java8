package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.reranker.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the scoping model's raw text into a validated {@link ScopingAssistantOutput}. Like the generic
 * parser it tolerantly extracts the first balanced JSON object (prose/code-fence resilient), then enforces the
 * scoping contract: a non-blank {@code assistantMessage} AND a non-blank {@code researchBriefMarkdown} are
 * required; {@code explorationMapMermaid} and {@code searchSuggestions} may be empty; a suggestion with a blank
 * query is rejected; {@code advice} defaults to NEUTRAL. A malformed or contract-violating answer is a typed
 * failure the caller answers with ONE bounded repair, then an honest error — never a fabricated turn.
 */
public final class ScopingAssistantOutputParser {

    /** A generous guard against pathological payloads (idea maps / briefs are prose, not documents). */
    private static final int MAX_FIELD_CHARS = 20000;

    public static final class Result {
        private final ScopingAssistantOutput output;
        private final String error;

        private Result(ScopingAssistantOutput output, String error) {
            this.output = output;
            this.error = error;
        }

        public boolean isOk() {
            return output != null;
        }

        public ScopingAssistantOutput getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }
    }

    private ScopingAssistantOutputParser() {
    }

    @SuppressWarnings("unchecked")
    public static Result parse(String rawModelText) {
        String json = TeamAgentTurnParser.extractJsonObject(rawModelText);
        if (json == null) {
            return fail("no JSON object found in the model answer");
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException malformed) {
            return fail("model answer was not valid JSON: " + malformed.getMessage());
        }
        if (!(root instanceof Map)) {
            return fail("model answer is not a JSON object");
        }
        Map<String, Object> object = (Map<String, Object>) root;

        String assistantMessage = asString(object.get("assistantMessage"));
        if (isBlank(assistantMessage)) {
            return fail("scoping answer has no assistantMessage");
        }
        String brief = asString(object.get("researchBriefMarkdown"));
        if (isBlank(brief)) {
            return fail("scoping answer has no researchBriefMarkdown");
        }
        if (brief.length() > MAX_FIELD_CHARS) {
            return fail("researchBriefMarkdown exceeds the size limit");
        }
        // A scoping answer is the COMPLETE current snapshot (RA-P6.5): a STRUCTURED exploration map + at
        // least one suggestion are required, so a helpful turn never blanks the workspace projection and a
        // brief-only interview reply is not a valid scoping turn. The model owns the map's content/hierarchy;
        // the app renders it (guaranteed-valid Mermaid), so a malformed Mermaid string can never reach the UI.
        ExplorationMap explorationMap = ExplorationMapParser.parse(object.get("explorationMap"));
        if (explorationMap == null) {
            return fail("scoping answer needs an exploration map");
        }

        List<SearchSuggestion> suggestions = new ArrayList<SearchSuggestion>();
        Object rawSuggestions = object.get("searchSuggestions");
        if (rawSuggestions instanceof List) {
            for (Object element : (List<Object>) rawSuggestions) {
                if (!(element instanceof Map)) {
                    continue;
                }
                Map<String, Object> suggestion = (Map<String, Object>) element;
                String query = asString(suggestion.get("query"));
                if (isBlank(query)) {
                    return fail("a search suggestion has a blank query");
                }
                int priority = asPositiveInt(suggestion.get("priority"), 1);
                if (priority <= 0) {
                    return fail("a search suggestion has a non-positive priority");
                }
                suggestions.add(new SearchSuggestion(query, asString(suggestion.get("purpose")), priority));
            }
        }
        if (suggestions.isEmpty()) {
            return fail("scoping answer needs at least one search suggestion");
        }

        PhaseAdvice advice = PhaseAdvice.neutral();
        Object rawAdvice = object.get("advice");
        if (rawAdvice instanceof Map) {
            Map<String, Object> adviceMap = (Map<String, Object>) rawAdvice;
            advice = new PhaseAdvice(
                    PhaseAdviceRecommendation.fromToken(asString(adviceMap.get("recommendation"))),
                    asString(adviceMap.get("reason")));
        }

        return new Result(new ScopingAssistantOutput(assistantMessage, brief, explorationMap, suggestions,
                advice), null);
    }

    private static Result fail(String error) {
        return new Result(null, error);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** MiniJson yields numbers as Double; accept an integral value, else the default. */
    private static int asPositiveInt(Object value, int fallback) {
        if (value instanceof Number) {
            return (int) Math.round(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            try {
                return (int) Math.round(Double.parseDouble(((String) value).trim()));
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }
        return fallback;
    }
}
