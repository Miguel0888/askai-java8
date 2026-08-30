package com.aresstack.askai.research.runtime.team;

import java.util.List;
import java.util.Map;

/**
 * The writing counterpart to {@code MiniJson} (a reader only): serializes the parsed
 * Map/List/String/Number/Boolean/null shapes back to compact JSON. Used where a model embeds a
 * JSON value INSIDE its answer (e.g. a concept branch) that must travel onward as JSON text —
 * the receiving side's STRICT parser stays the authority over validity.
 */
final class MiniJsonWriter {

    private MiniJsonWriter() {
    }

    @SuppressWarnings("unchecked")
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                append(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                append(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof String) {
            appendString(sb, (String) value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            // MiniJson reads all numbers as Double; render integral values without the ".0".
            if (d == Math.rint(d) && !Double.isInfinite(d)
                    && Math.abs(d) < 9007199254740992.0) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue());
        } else {
            appendString(sb, String.valueOf(value));
        }
    }

    private static void appendString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
        sb.append('"');
    }
}
