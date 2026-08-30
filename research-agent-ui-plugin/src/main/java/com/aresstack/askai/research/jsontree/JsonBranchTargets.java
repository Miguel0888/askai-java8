package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Resolves a {@link JsonBranchPath} against a document root — the one shared implementation of
 * "where does this host-side node handle point to", used by branch EXPORT and branch REPLACE
 * alike so both agree on every edge case. Resolution never mutates anything.
 */
final class JsonBranchTargets {

    /** The resolved target: the array-valued property plus everything needed to swap it later. */
    static final class Target {
        /** The object that directly holds the target property. */
        final JsonObject container;
        /** The array the container sits in, or {@code null} when the container IS the root. */
        final JsonArray containerParentArray;
        final int containerIndex;
        final String property;
        final JsonArray value;

        Target(JsonObject container, JsonArray containerParentArray, int containerIndex,
                String property, JsonArray value) {
            this.container = container;
            this.containerParentArray = containerParentArray;
            this.containerIndex = containerIndex;
            this.property = property;
            this.value = value;
        }
    }

    /** Outcome: exactly one of target or diagnostic. */
    static final class Result {
        final Target target;
        final JsonTreeDiagnostic diagnostic;

        private Result(Target target, JsonTreeDiagnostic diagnostic) {
            this.target = target;
            this.diagnostic = diagnostic;
        }
    }

    private JsonBranchTargets() {
    }

    static Result resolve(JsonElement documentRoot, JsonBranchPath path) {
        if (path == null || path.isEmpty()) {
            return notFound("$", "The target path is empty — there is no node to address.");
        }
        if (!documentRoot.isJsonObject()) {
            return notFound("$", "Branch editing requires a document with an OBJECT root, but "
                    + "the root is " + JsonBranchCompiler.kindOf(documentRoot) + ".");
        }
        JsonObject container = documentRoot.getAsJsonObject();
        JsonArray parentArray = null;
        int parentIndex = -1;
        int stepCount = path.getSteps().size();
        for (int i = 0; i < stepCount; i++) {
            JsonBranchPath.Step step = path.getSteps().get(i);
            String prefix = path.describePrefix(i + 1);
            JsonElement value = container.get(step.getProperty());
            if (value == null) {
                return notFound(prefix, "Property \"" + step.getProperty()
                        + "\" does not exist at this position.");
            }
            if (!value.isJsonArray()) {
                // The interpretation rule: only arrays are structural — an object or scalar ends
                // the path, so nothing behind it is addressable (settings/profiles case).
                return notFound(prefix, "Property \"" + step.getProperty() + "\" is "
                        + JsonBranchCompiler.kindOf(value)
                        + ", not an array — the path ends before it.");
            }
            JsonArray array = value.getAsJsonArray();
            if (i == stepCount - 1) {
                return new Result(new Target(container, parentArray, parentIndex,
                        step.getProperty(), array), null);
            }
            if (step.getElementIndex() < 0 || step.getElementIndex() >= array.size()) {
                return notFound(prefix, "Array \"" + step.getProperty() + "\" has "
                        + array.size() + " element(s); index " + step.getElementIndex()
                        + " does not exist.");
            }
            JsonElement element = array.get(step.getElementIndex());
            if (!element.isJsonObject()) {
                return notFound(prefix, "Element " + step.getElementIndex() + " of array \""
                        + step.getProperty() + "\" is " + JsonBranchCompiler.kindOf(element)
                        + ", not a container object — it cannot hold the next path step.");
            }
            parentArray = array;
            parentIndex = step.getElementIndex();
            container = element.getAsJsonObject();
        }
        throw new IllegalStateException("unreachable: loop always returns on the last step");
    }

    private static Result notFound(String path, String message) {
        return new Result(null, JsonTreeDiagnostic
                .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND, message)
                .path(path)
                .hint("The host-side target path no longer matches the document; re-export the "
                        + "branch from the current document.")
                .build());
    }
}
