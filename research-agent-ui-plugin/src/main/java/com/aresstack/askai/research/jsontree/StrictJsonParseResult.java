package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonElement;

/** Outcome of the SYNTAX layer: exactly one of raw element or diagnostic is present. */
public final class StrictJsonParseResult {

    private final JsonElement element;
    private final JsonTreeDiagnostic diagnostic;

    private StrictJsonParseResult(JsonElement element, JsonTreeDiagnostic diagnostic) {
        this.element = element;
        this.diagnostic = diagnostic;
    }

    static StrictJsonParseResult ok(JsonElement element) {
        return new StrictJsonParseResult(element, null);
    }

    static StrictJsonParseResult error(JsonTreeDiagnostic diagnostic) {
        return new StrictJsonParseResult(null, diagnostic);
    }

    public boolean isOk() {
        return diagnostic == null;
    }

    public JsonElement getElement() {
        return element;
    }

    public JsonTreeDiagnostic getDiagnostic() {
        return diagnostic;
    }
}
