package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NEUTRAL scope-update document a scoping turn sends to the host: the proposed operations, the open
 * issues and the proposed orientation lookups — as plain JSON.
 * <p>
 * The runtime deliberately does NOT know the host's domain objects here. The process boundary carries data,
 * not types, so both sides can evolve on their own; the host decodes this into its operations and decides
 * what to do with them. The runtime's job is to make sure only WELL-FORMED proposals cross: a malformed
 * operation is dropped with a reason instead of travelling on and being rejected invisibly later.
 */
public final class ScopeUpdateDocument {

    /** Operation kinds the host understands. An unknown kind never reaches the wire. */
    private static final List<String> KNOWN_KINDS = java.util.Arrays.asList(
            "setMission", "addFacet", "confirmFacet", "excludeFacet", "setFacetEmphasis",
            "setCrossCuttingEmphasis", "setDeliverable", "addDomain", "addContext", "addPerspective",
            "addConstraint", "addExclusion", "addTerminology", "setGeographicScope", "setTemporalScope",
            "addUnresolvedIssue", "resolveIssue");

    /** Fields that must be present and non-blank for a given kind. */
    private static final Map<String, List<String>> REQUIRED_FIELDS = requiredFields();

    private final List<Map<String, Object>> operations;
    private final List<Map<String, Object>> issues;
    private final List<Map<String, Object>> suggestions;
    private final List<String> rejections;

    private ScopeUpdateDocument(List<Map<String, Object>> operations, List<Map<String, Object>> issues,
                                List<Map<String, Object>> suggestions, List<String> rejections) {
        this.operations = operations;
        this.issues = issues;
        this.suggestions = suggestions;
        this.rejections = rejections;
    }

    /** True when this turn proposes nothing at all — then no wire line is sent. */
    public boolean isEmpty() {
        return operations.isEmpty() && issues.isEmpty() && suggestions.isEmpty();
    }

    /** What was dropped and why — surfaced in the technical trace, never silently swallowed. */
    public List<String> getRejections() {
        return rejections;
    }

    @SuppressWarnings("unchecked")
    public static ScopeUpdateDocument from(Object scopePatch, Object unresolvedIssues,
                                           Object orientationSuggestions) {
        List<Map<String, Object>> operations = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        List<String> rejections = new ArrayList<String>();

        Object rawOperations = scopePatch instanceof Map
                ? ((Map<String, Object>) scopePatch).get("operations") : scopePatch;
        for (Map<String, Object> operation : objects(rawOperations)) {
            String kind = text(operation.get("kind"));
            if (!KNOWN_KINDS.contains(kind)) {
                rejections.add("unknown operation kind '" + kind + "'");
                continue;
            }
            String missing = firstMissingField(kind, operation);
            if (missing != null) {
                rejections.add(kind + " without '" + missing + "'");
                continue;
            }
            operations.add(operation);
        }
        for (Map<String, Object> issue : objects(unresolvedIssues)) {
            if (text(issue.get("issueId")).isEmpty() || text(issue.get("description")).isEmpty()) {
                rejections.add("unresolvedIssue without issueId/description");
                continue;
            }
            issues.add(issue);
        }
        for (Map<String, Object> suggestion : objects(orientationSuggestions)) {
            // The label is what the user reads and must exist in its own right: falling back to the query
            // would show an engine query (possibly in another language) as UI text.
            if (text(suggestion.get("label")).isEmpty() || text(suggestion.get("query")).isEmpty()) {
                rejections.add("orientationSuggestion without label/query");
                continue;
            }
            suggestions.add(suggestion);
        }
        return new ScopeUpdateDocument(operations, issues, suggestions, rejections);
    }

    /** The canonical JSON the host decodes. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"operations\":").append(array(operations));
        sb.append(",\"unresolvedIssues\":").append(array(issues));
        sb.append(",\"orientationSuggestions\":").append(array(suggestions));
        return sb.append('}').toString();
    }

    private static Map<String, List<String>> requiredFields() {
        Map<String, List<String>> required = new LinkedHashMap<String, List<String>>();
        required.put("setMission", java.util.Arrays.asList("mission"));
        required.put("addFacet", java.util.Arrays.asList("facetId", "label"));
        required.put("confirmFacet", java.util.Arrays.asList("facetId"));
        required.put("excludeFacet", java.util.Arrays.asList("facetId"));
        required.put("setFacetEmphasis", java.util.Arrays.asList("facetId"));
        required.put("setCrossCuttingEmphasis", java.util.Arrays.asList("dimension"));
        required.put("addDomain", java.util.Arrays.asList("value"));
        required.put("addContext", java.util.Arrays.asList("value"));
        required.put("addPerspective", java.util.Arrays.asList("value"));
        required.put("addConstraint", java.util.Arrays.asList("value"));
        required.put("addExclusion", java.util.Arrays.asList("value"));
        required.put("addTerminology", java.util.Arrays.asList("value"));
        required.put("setGeographicScope", java.util.Arrays.asList("value"));
        required.put("setTemporalScope", java.util.Arrays.asList("value"));
        required.put("addUnresolvedIssue", java.util.Arrays.asList("issueId", "description"));
        required.put("resolveIssue", java.util.Arrays.asList("issueId"));
        return required;
    }

    private static String firstMissingField(String kind, Map<String, Object> operation) {
        List<String> required = REQUIRED_FIELDS.get(kind);
        if (required == null) {
            return null; // setDeliverable carries only optional fields
        }
        for (String field : required) {
            if (text(operation.get(field)).isEmpty()) {
                return field;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object raw) {
        List<Map<String, Object>> objects = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) {
            return objects;
        }
        for (Object element : (List<Object>) raw) {
            if (element instanceof Map) {
                objects.add((Map<String, Object>) element);
            }
        }
        return objects;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String array(List<Map<String, Object>> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            sb.append(object(values.get(index)));
        }
        return sb.append(']').toString();
    }

    private static String object(Map<String, Object> value) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String value(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Boolean || raw instanceof Number) {
            return String.valueOf(raw);
        }
        if (raw instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object element : (List<Object>) raw) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(value(element));
            }
            return sb.append(']').toString();
        }
        if (raw instanceof Map) {
            return object((Map<String, Object>) raw);
        }
        return quote(String.valueOf(raw));
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        sb.append(String.format("\\u%04x", (int) character));
                    } else {
                        sb.append(character);
                    }
                    break;
            }
        }
        return sb.append('"').toString();
    }
}
