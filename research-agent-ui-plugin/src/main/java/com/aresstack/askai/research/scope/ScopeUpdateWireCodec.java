package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.CoverageEmphasis;
import com.aresstack.askai.research.domain.scope.OrientationSuggestion;
import com.aresstack.askai.research.domain.scope.ResearchDeliverable;
import com.aresstack.askai.research.domain.scope.ScopePatch;
import com.aresstack.askai.research.domain.scope.ScopePatchOperation;
import com.aresstack.askai.research.domain.scope.ScopePatchOperations;
import com.aresstack.askai.research.domain.scope.ScopingTurnResult;
import com.aresstack.askai.research.domain.scope.SynthesisPolicy;
import com.aresstack.askai.research.domain.scope.UnresolvedScopeIssue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the NEUTRAL scope-update document the runtime sends into domain operations. The process boundary
 * deliberately carries plain JSON — {@code {"kind":"addFacet","facetId":...}} — never the domain's operation
 * objects: the two processes are versioned independently, and the wire must stay readable and diffable.
 *
 * <pre>
 * {
 *   "assistantMessage": "…",
 *   "operations": [ {"kind":"addFacet","facetId":"health","label":"Gesundheit","rationale":"…"} ],
 *   "unresolvedIssues": [ {"issueId":"taxonomy","description":"…","significance":"CRITICAL"} ],
 *   "orientationSuggestions": [ {"label":"Tradition kurz prüfen","query":"turkey ragout history",
 *                               "rationale":"…"} ]
 * }
 * </pre>
 *
 * Decoding is STRICT about what it accepts but never partially applies: an unknown operation kind or a
 * missing required field fails the whole document, so the conversation and the stored scope cannot drift
 * apart silently. The caller surfaces that failure to the user.
 */
public final class ScopeUpdateWireCodec {

    private ScopeUpdateWireCodec() {
    }

    /** A decoded update, or the concrete reason it could not be decoded. */
    public static final class Result {
        private final ScopingTurnResult turn;
        private final String error;

        private Result(ScopingTurnResult turn, String error) {
            this.turn = turn;
            this.error = error;
        }

        public boolean isOk() {
            return turn != null;
        }

        public ScopingTurnResult getTurn() {
            return turn;
        }

        public String getError() {
            return error == null ? "" : error;
        }
    }

    public static Result decode(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new Result(null, "empty scope update");
        }
        JsonObject document;
        try {
            document = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException notJson) {
            return new Result(null, "scope update is not a JSON object: " + notJson.getMessage());
        }
        try {
            List<ScopePatchOperation> operations = new ArrayList<ScopePatchOperation>();
            for (JsonElement element : array(document, "operations")) {
                operations.add(operation(element.getAsJsonObject()));
            }
            List<UnresolvedScopeIssue> issues = new ArrayList<UnresolvedScopeIssue>();
            for (JsonElement element : array(document, "unresolvedIssues")) {
                issues.add(issue(element.getAsJsonObject()));
            }
            List<OrientationSuggestion> suggestions = new ArrayList<OrientationSuggestion>();
            for (JsonElement element : array(document, "orientationSuggestions")) {
                JsonObject suggestion = element.getAsJsonObject();
                // The label is the user-visible text and must be present: falling back to the query would
                // put an English engine query on a German tag.
                suggestions.add(new OrientationSuggestion(required(suggestion, "label"),
                        required(suggestion, "query"), string(suggestion, "rationale")));
            }
            String message = string(document, "assistantMessage");
            return new Result(new ScopingTurnResult(message.isEmpty() ? "(no message)" : message,
                    new ScopePatch(operations), issues, suggestions), null);
        } catch (RuntimeException invalid) {
            return new Result(null, invalid.getMessage());
        }
    }

    private static ScopePatchOperation operation(JsonObject operation) {
        String kind = required(operation, "kind");
        if ("setMission".equals(kind)) {
            return ScopePatchOperations.setMission(required(operation, "mission"));
        }
        if ("addFacet".equals(kind)) {
            return ScopePatchOperations.addFacet(required(operation, "facetId"),
                    required(operation, "label"), string(operation, "rationale"));
        }
        if ("confirmFacet".equals(kind)) {
            return ScopePatchOperations.confirmFacet(required(operation, "facetId"),
                    string(operation, "rationale"));
        }
        if ("excludeFacet".equals(kind)) {
            return ScopePatchOperations.excludeFacet(required(operation, "facetId"),
                    string(operation, "rationale"));
        }
        if ("setFacetEmphasis".equals(kind)) {
            return ScopePatchOperations.setFacetEmphasis(required(operation, "facetId"),
                    enumValue(CoverageEmphasis.Importance.class, string(operation, "importance"),
                            CoverageEmphasis.Importance.MEDIUM),
                    enumValue(CoverageEmphasis.ResearchDepth.class, string(operation, "researchDepth"),
                            CoverageEmphasis.ResearchDepth.STANDARD),
                    integer(operation, "outputShareHint", CoverageEmphasis.NO_SHARE_HINT));
        }
        if ("setCrossCuttingEmphasis".equals(kind)) {
            return ScopePatchOperations.setCrossCuttingEmphasis(required(operation, "dimension"),
                    enumValue(CoverageEmphasis.Importance.class, string(operation, "importance"),
                            CoverageEmphasis.Importance.MEDIUM));
        }
        if ("setDeliverable".equals(kind)) {
            return ScopePatchOperations.setDeliverable(deliverable(operation));
        }
        if ("addTerminology".equals(kind)) {
            return ScopePatchOperations.addTerminology(required(operation, "value"));
        }
        if ("addExclusion".equals(kind)) {
            return ScopePatchOperations.addExclusion(required(operation, "value"));
        }
        if ("addDomain".equals(kind)) {
            return ScopePatchOperations.addDomain(required(operation, "value"));
        }
        if ("addContext".equals(kind)) {
            return ScopePatchOperations.addContext(required(operation, "value"));
        }
        if ("addPerspective".equals(kind)) {
            return ScopePatchOperations.addPerspective(required(operation, "value"));
        }
        if ("addConstraint".equals(kind)) {
            return ScopePatchOperations.addConstraint(required(operation, "value"));
        }
        if ("setGeographicScope".equals(kind)) {
            return ScopePatchOperations.setGeographicScope(required(operation, "value"));
        }
        if ("setTemporalScope".equals(kind)) {
            return ScopePatchOperations.setTemporalScope(required(operation, "value"));
        }
        if ("addUnresolvedIssue".equals(kind)) {
            return ScopePatchOperations.addUnresolvedIssue(issue(operation));
        }
        if ("resolveIssue".equals(kind)) {
            return ScopePatchOperations.resolveIssue(required(operation, "issueId"));
        }
        throw new IllegalArgumentException("unknown scope operation '" + kind + "'");
    }

    private static UnresolvedScopeIssue issue(JsonObject document) {
        List<String> affected = new ArrayList<String>();
        for (JsonElement element : array(document, "affectedFacetIds")) {
            if (element.isJsonPrimitive()) {
                affected.add(element.getAsString());
            }
        }
        return new UnresolvedScopeIssue(required(document, "issueId"), required(document, "description"),
                affected, enumValue(UnresolvedScopeIssue.Significance.class,
                        string(document, "significance"), UnresolvedScopeIssue.Significance.SIGNIFICANT));
    }

    private static ResearchDeliverable deliverable(JsonObject document) {
        SynthesisPolicy defaults = SynthesisPolicy.defaults();
        SynthesisPolicy policy = new SynthesisPolicy(
                bool(document, "categoryFirst", defaults.isCategoryFirst()),
                bool(document, "contrastRequired", defaults.isContrastRequired()),
                enumValue(SynthesisPolicy.RepetitiveEntityPolicy.class,
                        string(document, "repetitiveEntityPolicy"),
                        defaults.getRepetitiveEntityPolicy()),
                enumValue(SynthesisPolicy.ExamplePolicy.class, string(document, "examplePolicy"),
                        defaults.getExamplePolicy()));
        return new ResearchDeliverable(integer(document, "targetLengthMin", ResearchDeliverable.NO_LENGTH),
                integer(document, "targetLengthMax", ResearchDeliverable.NO_LENGTH),
                enumValue(ResearchDeliverable.LengthUnit.class, string(document, "lengthUnit"),
                        ResearchDeliverable.LengthUnit.UNSPECIFIED),
                policy);
    }

    private static JsonArray array(JsonObject document, String field) {
        JsonElement element = document.get(field);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("'" + field + "' must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String required(JsonObject document, String field) {
        String value = string(document, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing '" + field + "'");
        }
        return value;
    }

    private static String string(JsonObject document, String field) {
        JsonElement element = document.get(field);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString().trim();
    }

    private static int integer(JsonObject document, String field, int fallback) {
        JsonElement element = document.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject document, String field, boolean fallback) {
        JsonElement element = document.get(field);
        try {
            return element == null || !element.isJsonPrimitive() ? fallback : element.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return fallback;
        }
    }

    /** An unknown vocabulary value falls back; an unknown OPERATION does not — that would change meaning. */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
