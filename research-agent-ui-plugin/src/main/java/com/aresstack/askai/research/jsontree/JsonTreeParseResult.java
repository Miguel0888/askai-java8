package com.aresstack.askai.research.jsontree;

/** Outcome of the semantic layer: exactly one of tree or diagnostic is present. */
public final class JsonTreeParseResult {

    private final JsonTree tree;
    private final JsonTreeDiagnostic diagnostic;

    private JsonTreeParseResult(JsonTree tree, JsonTreeDiagnostic diagnostic) {
        this.tree = tree;
        this.diagnostic = diagnostic;
    }

    static JsonTreeParseResult ok(JsonTree tree) {
        return new JsonTreeParseResult(tree, null);
    }

    static JsonTreeParseResult error(JsonTreeDiagnostic diagnostic) {
        return new JsonTreeParseResult(null, diagnostic);
    }

    public boolean isOk() {
        return diagnostic == null;
    }

    public JsonTree getTree() {
        return tree;
    }

    public JsonTreeDiagnostic getDiagnostic() {
        return diagnostic;
    }
}
