package com.aresstack.askai.research.jsontree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The COMPILE-BEFORE-SWAP pipeline: a model answer NEVER touches the current valid document.
 * Order: stale check → strict branch compile → resolve the host-side target in a deep COPY →
 * graft (rename-aware, position-preserving) → re-validate the COMPLETE candidate through the
 * whole layer (strict parse → typed tree → structural projection) → only then commit as revision
 * {@code current + 1}. Every failure returns a diagnostic and leaves the input untouched —
 * "parse, compile, graft into candidate, validate candidate, commit" and never
 * "replace, hope, repair".
 */
public final class JsonBranchReplacer {

    private JsonBranchReplacer() {
    }

    public static BranchEditResult apply(String currentDocumentJson, long currentRevision,
            BranchEditRequest request) {
        // 1. Stale detection FIRST: the document may have moved while the model was thinking.
        if (request.getBaseRevision() != currentRevision) {
            return BranchEditResult.rejected(JsonTreeDiagnostic
                    .of(JsonTreeErrorCode.STALE_DOCUMENT_REVISION,
                            "The document changed while this branch was being edited. Expected "
                                    + "revision: " + request.getBaseRevision()
                                    + ". Current revision: " + currentRevision
                                    + ". The branch was not applied.")
                    .hint("Re-export the branch from the current document and redo the edit "
                            + "on top of it.")
                    .build());
        }
        // 2. Compile the model's branch (strict syntax + root contract) before touching anything.
        JsonBranchCompiler.Result compiled = JsonBranchCompiler.compile(request.getBranchJson());
        if (!compiled.isOk()) {
            return BranchEditResult.rejected(compiled.getDiagnostic());
        }
        // 3. The current document itself must parse — a caller-side precondition, reported honestly.
        StrictJsonParseResult current = StrictJsonParser.parse(currentDocumentJson);
        if (!current.isOk()) {
            return BranchEditResult.rejected(JsonTreeDiagnostic
                    .of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                            "The CURRENT document is not valid JSON — nothing can be grafted "
                                    + "into it. Underlying problem: "
                                    + current.getDiagnostic().getMessage())
                    .build());
        }
        // 4. Everything from here on happens on a deep copy — the valid state stays untouched.
        JsonElement candidateRoot = current.getElement().deepCopy();
        JsonBranchTargets.Result resolved =
                JsonBranchTargets.resolve(candidateRoot, request.getTargetPath());
        if (resolved.diagnostic != null) {
            return BranchEditResult.rejected(resolved.diagnostic);
        }
        JsonTreeDiagnostic graftProblem = graft(candidateRoot, resolved.target,
                compiled.getBranch(), request.getTargetPath());
        if (graftProblem != null) {
            return BranchEditResult.rejected(graftProblem);
        }
        // 5. Validate the COMPLETE candidate through the full pipeline before it may become truth.
        String candidateJson = JsonDocuments.write(candidateRoot);
        JsonTreeParseResult validated = JsonTreeParser.parse(candidateJson);
        if (!validated.isOk()) {
            return BranchEditResult.rejected(JsonTreeDiagnostic
                    .of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                            "The grafted candidate document failed re-validation and was "
                                    + "discarded; the previous valid state stays active. "
                                    + "Underlying problem: "
                                    + validated.getDiagnostic().getMessage())
                    .path(validated.getDiagnostic().getPath())
                    .build());
        }
        StructuralTreeExtractor.extract(validated.getTree()); // must project without throwing
        return BranchEditResult.committed(candidateJson, currentRevision + 1);
    }

    /**
     * Swap the target property for the compiled branch. A renamed branch root replaces the old
     * property AT ITS POSITION (the containing object is rebuilt in order); a rename that would
     * collide with an existing sibling is refused.
     */
    private static JsonTreeDiagnostic graft(JsonElement candidateRoot,
            JsonBranchTargets.Target target, JsonBranchCompiler.CompiledBranch branch,
            JsonBranchPath path) {
        String oldName = target.property;
        String newName = branch.getName();
        if (newName.equals(oldName)) {
            // Same key: LinkedTreeMap replaces the value in place, order is preserved for free.
            target.container.add(oldName, branch.getValue());
            return null;
        }
        if (target.container.has(newName)) {
            return JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "Renaming the branch root from \"" + oldName + "\" to \"" + newName
                            + "\" collides with an existing sibling property of the same name.")
                    .path(path.describe())
                    .hint("Choose a name that is unique among the siblings, or keep the "
                            + "original branch root name.")
                    .build();
        }
        JsonObject rebuilt = new JsonObject();
        for (Map.Entry<String, JsonElement> property : target.container.entrySet()) {
            if (property.getKey().equals(oldName)) {
                rebuilt.add(newName, branch.getValue());
            } else {
                rebuilt.add(property.getKey(), property.getValue());
            }
        }
        if (target.containerParentArray == null) {
            // The container is the document root object: swap its content in place.
            replaceAllProperties(candidateRoot.getAsJsonObject(), rebuilt);
        } else {
            target.containerParentArray.set(target.containerIndex, rebuilt);
        }
        return null;
    }

    private static void replaceAllProperties(JsonObject object, JsonObject replacement) {
        for (String key : object.keySet().toArray(new String[0])) {
            object.remove(key);
        }
        for (Map.Entry<String, JsonElement> property : replacement.entrySet()) {
            object.add(property.getKey(), property.getValue());
        }
    }
}
