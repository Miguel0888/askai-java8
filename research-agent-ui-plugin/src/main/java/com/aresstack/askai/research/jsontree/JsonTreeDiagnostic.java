package com.aresstack.askai.research.jsontree;

/**
 * A neutral, structured diagnostic — the safety boundary's answer to malformed model output. Raw
 * Gson exceptions never leave this layer; instead every failure is reported with a machine code,
 * a human/LLM-readable message and as much position information (line, column, JSONPath-style
 * path, expected/actual, repair hint) as is RELIABLY available. Fields that could not be
 * determined stay {@code null} (or {@code -1} for line/column) — never guessed.
 */
public final class JsonTreeDiagnostic {

    private final JsonTreeErrorCode code;
    private final String message;
    private final int line;
    private final int column;
    private final String path;
    private final String expected;
    private final String actual;
    private final String hint;

    JsonTreeDiagnostic(JsonTreeErrorCode code, String message, int line, int column,
            String path, String expected, String actual, String hint) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.line = line;
        this.column = column;
        this.path = path;
        this.expected = expected;
        this.actual = actual;
        this.hint = hint;
    }

    public static Builder of(JsonTreeErrorCode code, String message) {
        return new Builder(code, message);
    }

    public JsonTreeErrorCode getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /** 1-based line of the error, or {@code -1} when unknown. */
    public int getLine() {
        return line;
    }

    /** 1-based column of the error, or {@code -1} when unknown. */
    public int getColumn() {
        return column;
    }

    /** JSONPath-style location like {@code $.FreeRTOS[0].Tasks}, or {@code null} when unknown. */
    public String getPath() {
        return path;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }

    public String getHint() {
        return hint;
    }

    /**
     * The feedback block meant to be sent VERBATIM to the model that produced the rejected JSON:
     * code first, then position, then the explanation and the repair hint.
     */
    public String describeForModel() {
        StringBuilder sb = new StringBuilder(code.name()).append('\n');
        if (line > 0) {
            sb.append("Line ").append(line);
            if (column > 0) {
                sb.append(", column ").append(column);
            }
            sb.append('\n');
        }
        if (path != null && !path.isEmpty()) {
            sb.append("Path: ").append(path).append('\n');
        }
        sb.append(message);
        if (expected != null && actual != null) {
            sb.append(" Expected ").append(expected).append(" but found ").append(actual).append('.');
        } else if (expected != null) {
            sb.append(" Expected ").append(expected).append('.');
        }
        if (hint != null && !hint.isEmpty()) {
            sb.append("\nHint: ").append(hint);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describeForModel();
    }

    /** Fluent construction so call sites only spell out the fields they actually know. */
    public static final class Builder {
        private final JsonTreeErrorCode code;
        private final String message;
        private int line = -1;
        private int column = -1;
        private String path;
        private String expected;
        private String actual;
        private String hint;

        private Builder(JsonTreeErrorCode code, String message) {
            this.code = code;
            this.message = message;
        }

        public Builder at(int line, int column) {
            this.line = line;
            this.column = column;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder expected(String expected) {
            this.expected = expected;
            return this;
        }

        public Builder actual(String actual) {
            this.actual = actual;
            return this;
        }

        public Builder hint(String hint) {
            this.hint = hint;
            return this;
        }

        public JsonTreeDiagnostic build() {
            return new JsonTreeDiagnostic(code, message, line, column, path, expected, actual, hint);
        }
    }
}
