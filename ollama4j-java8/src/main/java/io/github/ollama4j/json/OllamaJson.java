package io.github.ollama4j.json;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class OllamaJson {

    private OllamaJson() {
    }

    public static Object parse(String json) {
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("javascript");
            if (engine == null) {
                throw new IllegalStateException("No JavaScript engine available for JSON parsing.");
            }
            return engine.eval("Java.asJSONCompatible(" + json + ")");
        } catch (ScriptException ex) {
            throw new IllegalArgumentException("Invalid JSON: " + ex.getMessage(), ex);
        }
    }

    public static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            Iterator iterator = ((Map) value).entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry) iterator.next();
                builder.append(quote(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof List) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            Iterator iterator = ((List) value).iterator();
            while (iterator.hasNext()) {
                builder.append(toJson(iterator.next()));
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append(']');
            return builder.toString();
        }
        return quote(String.valueOf(value));
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                builder.append('\\').append(c);
            } else if (c == '\n') {
                builder.append("\\n");
            } else if (c == '\r') {
                builder.append("\\r");
            } else if (c == '\t') {
                builder.append("\\t");
            } else if (c < 32) {
                String hex = Integer.toHexString(c);
                builder.append("\\u");
                for (int j = hex.length(); j < 4; j++) {
                    builder.append('0');
                }
                builder.append(hex);
            } else {
                builder.append(c);
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
