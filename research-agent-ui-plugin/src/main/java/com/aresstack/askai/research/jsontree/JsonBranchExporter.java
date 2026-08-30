package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Exports ONE branch of a document for model editing — the model never receives the whole
 * concept paper. Two flavours: {@link #exportBranch full} (the property with its complete array
 * value, for tools that may see metadata) and {@link #exportStructuralBranch structural}
 * (array-valued properties only — the later mindmap tool's contract: it can then by construction
 * change nothing BUT array structure). Both render the {@code {"Name": [ ... ]}} shape the
 * {@link JsonBranchCompiler} accepts back. Export never mutates the document.
 */
public final class JsonBranchExporter {

    /** Outcome: exactly one of branch JSON or diagnostic. */
    public static final class Result {
        private final String branchJson;
        private final JsonTreeDiagnostic diagnostic;

        private Result(String branchJson, JsonTreeDiagnostic diagnostic) {
            this.branchJson = branchJson;
            this.diagnostic = diagnostic;
        }

        public boolean isOk() {
            return diagnostic == null;
        }

        public String getBranchJson() {
            return branchJson;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    private JsonBranchExporter() {
    }

    /** The branch with everything in it (structure, scalars, opaque objects). */
    public static Result exportBranch(String documentJson, JsonBranchPath path) {
        return export(documentJson, path, false);
    }

    /**
     * The branch reduced to array-valued properties only. Container objects keep their element
     * grouping; containers without any structural property (and scalar / anonymous-array
     * elements) are omitted — an empty structural branch is {@code {"Name": []}}.
     */
    public static Result exportStructuralBranch(String documentJson, JsonBranchPath path) {
        return export(documentJson, path, true);
    }

    private static Result export(String documentJson, JsonBranchPath path, boolean structural) {
        StrictJsonParseResult parsed = StrictJsonParser.parse(documentJson);
        if (!parsed.isOk()) {
            return new Result(null, parsed.getDiagnostic());
        }
        JsonBranchTargets.Result resolved = JsonBranchTargets.resolve(parsed.getElement(), path);
        if (resolved.diagnostic != null) {
            return new Result(null, resolved.diagnostic);
        }
        JsonObject branch = new JsonObject();
        branch.add(resolved.target.property, structural
                ? structuralArray(resolved.target.value)
                : resolved.target.value.deepCopy());
        return new Result(JsonDocuments.write(branch), null);
    }

    private static JsonArray structuralArray(JsonArray array) {
        JsonArray out = new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue; // scalars and anonymous nested arrays carry no NAMED structure
            }
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> property : element.getAsJsonObject().entrySet()) {
                if (property.getValue().isJsonArray()) {
                    kept.add(property.getKey(),
                            structuralArray(property.getValue().getAsJsonArray()));
                }
            }
            if (kept.size() > 0) {
                out.add(kept);
            }
        }
        return out;
    }
}
