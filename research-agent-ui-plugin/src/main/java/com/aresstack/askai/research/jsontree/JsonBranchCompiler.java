package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * COMPILES a model-returned branch before anything may touch the document: strict parse, then the
 * branch-root contract — the branch must be ONE object with EXACTLY ONE array-valued property,
 * i.e. exactly one root {@link ArrayNode} after structural extraction. That contract is what lets
 * the later mindmap tool change ONLY array structure (metadata travels through a different tool).
 */
public final class JsonBranchCompiler {

    /** A branch that passed syntax + root contract: its (possibly renamed) name and array value. */
    public static final class CompiledBranch {
        private final String name;
        private final JsonArray value;

        CompiledBranch(String name, JsonArray value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public JsonArray getValue() {
            return value;
        }
    }

    /** Outcome: exactly one of branch or diagnostic. */
    public static final class Result {
        private final CompiledBranch branch;
        private final JsonTreeDiagnostic diagnostic;

        private Result(CompiledBranch branch, JsonTreeDiagnostic diagnostic) {
            this.branch = branch;
            this.diagnostic = diagnostic;
        }

        public boolean isOk() {
            return diagnostic == null;
        }

        public CompiledBranch getBranch() {
            return branch;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    private JsonBranchCompiler() {
    }

    public static Result compile(String branchJson) {
        StrictJsonParseResult parsed = StrictJsonParser.parse(branchJson);
        if (!parsed.isOk()) {
            return new Result(null, parsed.getDiagnostic());
        }
        JsonElement root = parsed.getElement();
        if (!root.isJsonObject()) {
            return invalidRoot("The branch root must be a JSON object, not "
                    + kindOf(root) + ".");
        }
        JsonObject object = root.getAsJsonObject();
        if (object.size() != 1) {
            return invalidRoot("The branch object must contain EXACTLY ONE property (the branch "
                    + "root), but it contains " + object.size() + ".");
        }
        Map.Entry<String, JsonElement> only = object.entrySet().iterator().next();
        if (!only.getValue().isJsonArray()) {
            return invalidRoot("The branch root property \"" + only.getKey()
                    + "\" must have an ARRAY value, not " + kindOf(only.getValue()) + ".");
        }
        return new Result(new CompiledBranch(only.getKey(), only.getValue().getAsJsonArray()), null);
    }

    private static Result invalidRoot(String message) {
        return new Result(null, JsonTreeDiagnostic
                .of(JsonTreeErrorCode.INVALID_BRANCH_ROOT, message)
                .path("$")
                .expected("an object with exactly one array-valued property")
                .hint("Return the branch in the shape {\"Name\": [ ... ]} — one property, whose "
                        + "value is the array of the branch's children.")
                .build());
    }

    static String kindOf(JsonElement element) {
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? "a string"
                : primitive.isBoolean() ? "a boolean" : "a number";
    }
}
