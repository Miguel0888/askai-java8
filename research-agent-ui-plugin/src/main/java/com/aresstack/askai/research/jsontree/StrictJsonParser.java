package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SYNTAX layer only: parses text into a raw Gson element tree under STRICT RFC-8259 rules and
 * turns every failure into a {@link JsonTreeDiagnostic} — Gson exceptions never escape. Strict
 * means: no unquoted or single-quoted names/strings, no comments, no NaN/Infinity, no trailing
 * garbage, no silent repair of any kind (this layer diagnoses; the model repairs).
 *
 * <p>Deliberately hand-driven over {@link JsonReader} instead of {@code JsonParser.parseReader}:
 * Gson's JsonParser forces the reader into LENIENT mode, which would silently accept exactly the
 * JavaScript-isms this boundary exists to reject. Numbers are materialized as {@link BigDecimal}
 * so their value survives round-trips unchanged.</p>
 */
public final class StrictJsonParser {

    /** "&lt;why&gt; at line 12 column 5 path $.a[0].b" — the position tail of Gson messages. */
    private static final Pattern GSON_POSITION =
            Pattern.compile("(.*?)\\s+at line (\\d+) column (\\d+)(?: path (\\S+))?\\s*$",
                    Pattern.DOTALL);
    private static final Pattern EXPECTED =
            Pattern.compile("^Expected (.+)$");

    private StrictJsonParser() {
    }

    /** Parse strictly; the result carries EITHER the element OR a diagnostic, never both. */
    public static StrictJsonParseResult parse(String jsonText) {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            return StrictJsonParseResult.error(
                    JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                            "The document is empty — there is no JSON value to parse.")
                            .expected("a JSON value").build());
        }
        JsonReader reader = new JsonReader(new StringReader(jsonText));
        reader.setLenient(false);
        try {
            JsonElement root = readValue(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return StrictJsonParseResult.error(
                        JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                                "Trailing content after the end of the JSON document.")
                                .path(safePath(reader))
                                .expected("end of input")
                                .hint("Exactly one top-level JSON value is allowed; remove "
                                        + "everything after it.")
                                .build());
            }
            return StrictJsonParseResult.ok(root);
        } catch (Exception failure) {
            return StrictJsonParseResult.error(translate(failure, reader));
        }
    }

    private static JsonElement readValue(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.peek() != JsonToken.END_ARRAY) {
                    array.add(readValue(reader));
                }
                reader.endArray();
                return array;
            }
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.peek() == JsonToken.NAME) {
                    object.add(reader.nextName(), readValue(reader));
                }
                reader.endObject();
                return object;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                // nextString on a NUMBER token yields the literal; BigDecimal preserves the value
                // exactly (the reader's strict grammar already rejected malformed literals).
                return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new MalformedJsonException("Expected a JSON value but found " + token);
        }
    }

    // ------------------------------------------------------------------ failure translation

    /** Distill line/column/path/expected + a repair hint out of Gson's exception wording. */
    private static JsonTreeDiagnostic translate(Exception failure, JsonReader reader) {
        String raw = failure.getMessage() == null ? "" : failure.getMessage();
        // Newer Gson appends "\nSee https://…/Troubleshooting.md…" — strip it, it would defeat
        // the position regex and is useless to a model.
        int seeAlso = raw.indexOf("\nSee https://");
        if (seeAlso >= 0) {
            raw = raw.substring(0, seeAlso);
        }
        int line = -1;
        int column = -1;
        String path = null;
        String core = raw;
        Matcher position = GSON_POSITION.matcher(raw);
        if (position.matches()) {
            core = position.group(1).trim();
            line = Integer.parseInt(position.group(2));
            column = Integer.parseInt(position.group(3));
            path = position.group(4);
        }
        if (path == null) {
            path = safePath(reader);
        }
        JsonTreeDiagnostic.Builder builder;
        if (failure instanceof EOFException || core.startsWith("End of input")) {
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                    "Unexpected end of input — the document stops before the JSON value is complete.")
                    .hint("A closing bracket or value is missing; complete the structure that is "
                            + "still open at this position.");
        } else if (core.contains("setLenient") || core.contains("setStrictness")
                || core.startsWith("Malformed JSON")) {
            // Gson's blanket message for every JavaScript-ism (unquoted names, single quotes, …).
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                    "Malformed JSON: strict JSON is required — no unquoted property names, no "
                            + "single-quoted strings, no comments, no NaN/Infinity.")
                    .hint("Write property names and strings in double quotes and use only "
                            + "standard JSON syntax.");
        } else if (core.startsWith("Unterminated object")) {
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR, core + ".")
                    .expected("',' or '}'")
                    .hint("Check the object that is still open at this position — a comma or "
                            + "closing '}' is missing.");
        } else if (core.startsWith("Unterminated array")) {
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR, core + ".")
                    .expected("',' or ']'")
                    .hint("Check the array that is still open at this position — a comma or "
                            + "closing ']' is missing.");
        } else if (core.startsWith("Unterminated string")) {
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR, core + ".")
                    .expected("closing '\"'")
                    .hint("The string that starts before this position is never closed.");
        } else if (core.startsWith("Invalid escape sequence") || core.contains("escape sequence")) {
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                    "Invalid escape sequence inside a string.")
                    .hint("Only \\\" \\\\ \\/ \\b \\f \\n \\r \\t and \\uXXXX are valid escapes.");
        } else {
            builder = JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                    core.isEmpty() ? "The text is not valid JSON." : core + ".");
            Matcher expected = EXPECTED.matcher(core);
            if (expected.matches()) {
                builder.expected(expected.group(1));
            }
        }
        return builder.at(line, column).path(path).build();
    }

    /** The reader's current JSONPath — best effort, {@code null} when even that throws. */
    private static String safePath(JsonReader reader) {
        try {
            String path = reader.getPath();
            return path == null || path.isEmpty() ? null : path;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
