package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * Serializes a {@link ScopingAssistantOutput} into its canonical JSON — the SAME shape the scoping model emits
 * and that {@link ScopingAssistantOutputParser} reads back losslessly, so the scoping history round-trips with
 * ALL of its fields (brief, map, suggestions, advice), never just the visible message. Hand-rolled writer:
 * {@code MiniJson} is a reader only.
 */
public final class ScopingAssistantOutputCodec {

    private ScopingAssistantOutputCodec() {
    }

    public static String toJson(ScopingAssistantOutput output) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        writeKey(sb, "assistantMessage").append(':');
        writeString(sb, output.getAssistantMessage());
        sb.append(',');
        writeKey(sb, "researchBriefMarkdown").append(':');
        writeString(sb, output.getResearchBriefMarkdown());
        sb.append(',');
        writeKey(sb, "searchSuggestions").append(":[");
        List<SearchSuggestion> suggestions = output.getSearchSuggestions();
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SearchSuggestion s = suggestions.get(i);
            sb.append('{');
            writeKey(sb, "query").append(':');
            writeString(sb, s.getQuery());
            sb.append(',');
            writeKey(sb, "purpose").append(':');
            writeString(sb, s.getPurpose());
            sb.append(',');
            writeKey(sb, "priority").append(':').append(s.getPriority());
            sb.append('}');
        }
        sb.append(']');
        sb.append(',');
        writeKey(sb, "advice").append(":{");
        writeKey(sb, "recommendation").append(':');
        writeString(sb, output.getAdvice().getRecommendation().name());
        sb.append(',');
        writeKey(sb, "reason").append(':');
        writeString(sb, output.getAdvice().getReason());
        sb.append('}');
        // The concept step stays in the canonical history so later turns can SEE what the
        // assistant did with the tool.
        ConceptAction action = output.getConceptAction();
        if (action != null) {
            sb.append(',');
            writeKey(sb, "conceptAction").append(":{");
            writeKey(sb, "type").append(':');
            writeString(sb, action.getType().name().toLowerCase(java.util.Locale.ROOT));
            if (action.getType() == ConceptAction.Type.ADD) {
                sb.append(',');
                writeKey(sb, "parent").append(':');
                writeSegments(sb, action.getParent());
                sb.append(',');
                writeKey(sb, "name").append(':');
                writeString(sb, action.getName());
            } else {
                sb.append(',');
                writeKey(sb, "path").append(':');
                writeSegments(sb, action.getPath());
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }


    private static void writeSegments(StringBuilder sb, java.util.List<String> segments) {
        sb.append('[');
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeString(sb, segments.get(i));
        }
        sb.append(']');
    }

    private static StringBuilder writeKey(StringBuilder sb, String key) {
        return writeString(sb, key);
    }

    private static StringBuilder writeString(StringBuilder sb, String value) {
        sb.append('"');
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"');
    }
}
