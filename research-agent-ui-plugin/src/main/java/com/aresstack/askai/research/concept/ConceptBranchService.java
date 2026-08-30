package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.jsontree.BranchEditRequest;
import com.aresstack.askai.research.jsontree.BranchEditResult;
import com.aresstack.askai.research.jsontree.JsonBranchCompiler;
import com.aresstack.askai.research.jsontree.JsonBranchExporter;
import com.aresstack.askai.research.jsontree.JsonBranchPath;
import com.aresstack.askai.research.jsontree.JsonBranchReplacer;
import com.aresstack.askai.research.jsontree.JsonTreeDiagnostic;
import com.aresstack.askai.research.jsontree.JsonTreeErrorCode;
import com.aresstack.askai.research.jsontree.JsonTreeParseResult;
import com.aresstack.askai.research.jsontree.JsonTreeParser;
import com.aresstack.askai.research.jsontree.StrictJsonParseResult;
import com.aresstack.askai.research.jsontree.StrictJsonParser;
import com.aresstack.askai.research.store.FileConceptStore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The POLICY layer between the (later) concept tools and the neutral jsontree machinery — the
 * one place that makes the Konzeptpapier editable in bites, never regenerated:
 * <ul>
 * <li>works exclusively on the {@code concept} array of the document envelope (title/subtitle,
 *     and later book sections like outline/content/style, are a different tool's business —
 *     each section gets its OWN view and tool contract on the shared JsonTree; this service's
 *     name-chain addressing is the CONCEPT contract and would be wrong for a manuscript of
 *     repeating {@code paragraph} blocks, which must address by array position),</li>
 * <li>addresses nodes by NAME chains and hands out opaque branch HANDLES — the model's JSON
 *     stays free of technical addresses, the host alone maps handle → path + base revision,</li>
 * <li>a depth-limited read yields a READ-ONLY handle: writing back a pruned branch would
 *     silently wipe the pruned grandchildren, so editing requires a full-depth read first,</li>
 * <li>non-destructive by default: a refinement that silently drops existing structural nodes
 *     is rejected as {@link JsonTreeErrorCode#STRUCTURE_LOSS_DETECTED} — a node that merely
 *     MOVED elsewhere within the branch is recognized and allowed; removal is its own
 *     explicit operation,</li>
 * <li>every write runs the full compile-before-swap pipeline and lands in the
 *     {@link FileConceptStore}, whose working revision is the stale-branch guard.</li>
 * </ul>
 * All methods are synchronized — one concept, strictly serialized edits.
 */
public final class ConceptBranchService {

    /** The one property of the envelope this service ever touches. */
    public static final String CONCEPT_PROPERTY = "concept";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final FileConceptStore store;
    private final Map<String, Handle> handles = new HashMap<String, Handle>();
    private long handleCounter;

    public ConceptBranchService(FileConceptStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ results

    /** Outcome of a read: branch JSON plus orientation metadata, or a diagnostic. */
    public static final class ReadResult {
        private final String handleId;
        private final long workingRevision;
        private final String branchJson;
        private final String parentName;
        private final List<String> siblingNames;
        private final boolean editable;
        private final JsonTreeDiagnostic diagnostic;

        private ReadResult(String handleId, long workingRevision, String branchJson,
                String parentName, List<String> siblingNames, boolean editable,
                JsonTreeDiagnostic diagnostic) {
            this.handleId = handleId;
            this.workingRevision = workingRevision;
            this.branchJson = branchJson;
            this.parentName = parentName;
            this.siblingNames = siblingNames;
            this.editable = editable;
            this.diagnostic = diagnostic;
        }

        public boolean isOk() {
            return diagnostic == null;
        }

        public String getHandleId() {
            return handleId;
        }

        public long getWorkingRevision() {
            return workingRevision;
        }

        public String getBranchJson() {
            return branchJson;
        }

        /** The enclosing concept node's name, or {@code null} for a top-level read. */
        public String getParentName() {
            return parentName;
        }

        /** Structural neighbours around the target — enough to avoid duplicate concepts. */
        public List<String> getSiblingNames() {
            return siblingNames;
        }

        /** {@code false} for depth-limited reads: orientation only, never a write base. */
        public boolean isEditable() {
            return editable;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    /** Outcome of an update/remove: the new working revision, or a diagnostic. */
    public static final class EditResult {
        private final boolean applied;
        private final long newRevision;
        private final JsonTreeDiagnostic diagnostic;

        private EditResult(boolean applied, long newRevision, JsonTreeDiagnostic diagnostic) {
            this.applied = applied;
            this.newRevision = newRevision;
            this.diagnostic = diagnostic;
        }

        public boolean isApplied() {
            return applied;
        }

        public long getNewRevision() {
            return newRevision;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    private static final class Handle {
        final JsonBranchPath path;
        final long baseRevision;
        final boolean editable;

        Handle(JsonBranchPath path, long baseRevision, boolean editable) {
            this.path = path;
            this.baseRevision = baseRevision;
            this.editable = editable;
        }
    }

    // ------------------------------------------------------------------ read

    /**
     * Read the whole working surface ({@code names} empty) or one branch addressed by its name
     * chain. {@code depth <= 0} reads the full subtree and yields an EDITABLE handle;
     * {@code depth >= 1} prunes structural children below that depth to {@code []} and yields a
     * read-only orientation handle.
     */
    public synchronized ReadResult readBranch(List<String> names, int depth) {
        pruneExpiredHandles();
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return error(parsed.getDiagnostic());
        }
        Resolution resolution = resolve(parsed.getElement(), names);
        if (resolution.diagnostic != null) {
            return error(resolution.diagnostic);
        }
        JsonBranchExporter.Result export =
                JsonBranchExporter.exportBranch(document, resolution.path);
        if (!export.isOk()) {
            return error(export.getDiagnostic());
        }
        String branchJson = export.getBranchJson();
        boolean editable = depth <= 0;
        if (!editable) {
            branchJson = pruneToDepth(branchJson, depth);
        }
        String handleId = "b-" + Long.toHexString(++handleCounter);
        handles.put(handleId, new Handle(resolution.path, store.workingRevision(), editable));
        return new ReadResult(handleId, store.workingRevision(), branchJson,
                resolution.parentName, resolution.siblingNames, editable, null);
    }

    // ------------------------------------------------------------------ update

    /** Non-destructive refinement — the default the (later) concept_update tool uses. */
    public synchronized EditResult updateBranch(String handleId, String branchJson) {
        return updateBranch(handleId, branchJson, false);
    }

    public synchronized EditResult updateBranch(String handleId, String branchJson,
            boolean allowRemovals) {
        Handle handle = handles.get(handleId);
        if (handle == null) {
            return editError(unknownHandle(handleId));
        }
        if (!handle.editable) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "This branch handle came from a DEPTH-LIMITED read and is read-only — "
                            + "writing a pruned branch back would silently delete the pruned "
                            + "children.")
                    .hint("Read the branch again without a depth limit, then edit that "
                            + "full branch.")
                    .build());
        }
        String document = store.effectiveContent();
        long currentRevision = store.workingRevision();
        if (handle.baseRevision != currentRevision) {
            return editError(stale(handle.baseRevision, currentRevision));
        }
        if (!allowRemovals) {
            JsonTreeDiagnostic loss = detectStructureLoss(document, handle.path, branchJson);
            if (loss != null) {
                return editError(loss);
            }
        }
        BranchEditResult result = JsonBranchReplacer.apply(document, currentRevision,
                new BranchEditRequest(handle.baseRevision, handle.path, branchJson));
        if (!result.isCommitted()) {
            return editError(result.getDiagnostic());
        }
        long newRevision = store.commitWorking(result.getDocumentJson(),
                System.currentTimeMillis());
        return new EditResult(true, newRevision, null);
    }

    // ------------------------------------------------------------------ remove

    /** DELIBERATELY destructive: removes the addressed node with its whole subtree. */
    public synchronized EditResult removeBranch(String handleId) {
        Handle handle = handles.get(handleId);
        if (handle == null) {
            return editError(unknownHandle(handleId));
        }
        if (handle.path.getSteps().size() <= 1) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "The concept's working surface itself cannot be removed.")
                    .path(handle.path.describe())
                    .build());
        }
        String document = store.effectiveContent();
        long currentRevision = store.workingRevision();
        if (handle.baseRevision != currentRevision) {
            return editError(stale(handle.baseRevision, currentRevision));
        }
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return editError(parsed.getDiagnostic());
        }
        JsonElement candidate = parsed.getElement().deepCopy();
        JsonTreeDiagnostic removal = removeAt(candidate, handle.path);
        if (removal != null) {
            return editError(removal);
        }
        String candidateJson = GSON.toJson(candidate);
        JsonTreeParseResult validated = JsonTreeParser.parse(candidateJson);
        if (!validated.isOk()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                    "The document after the removal failed re-validation and was discarded. "
                            + "Underlying problem: " + validated.getDiagnostic().getMessage())
                    .build());
        }
        long newRevision = store.commitWorking(candidateJson, System.currentTimeMillis());
        return new EditResult(true, newRevision, null);
    }

    // ------------------------------------------------------------------ name-chain resolution

    private static final class Resolution {
        JsonBranchPath path;
        String parentName;
        List<String> siblingNames = new ArrayList<String>();
        JsonTreeDiagnostic diagnostic;
    }

    /**
     * Walk the name chain from the envelope root. Unlike the index-based {@link JsonBranchPath}
     * steps, the service finds the container ELEMENT for each hop by scanning for the first
     * array element that carries the next name — the model addresses concepts by name only.
     */
    private static Resolution resolve(JsonElement documentRoot, List<String> names) {
        Resolution resolution = new Resolution();
        if (!documentRoot.isJsonObject()
                || !documentRoot.getAsJsonObject().has(CONCEPT_PROPERTY)
                || !documentRoot.getAsJsonObject().get(CONCEPT_PROPERTY).isJsonArray()) {
            resolution.diagnostic = JsonTreeDiagnostic
                    .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                            "The concept document has no \"" + CONCEPT_PROPERTY
                                    + "\" array — the working surface is missing.")
                    .path("$").build();
            return resolution;
        }
        List<String> fullNames = new ArrayList<String>();
        fullNames.add(CONCEPT_PROPERTY);
        if (names != null) {
            fullNames.addAll(names);
        }
        List<JsonBranchPath.Step> steps = new ArrayList<JsonBranchPath.Step>();
        JsonObject container = documentRoot.getAsJsonObject();
        JsonArray parentArray = null;
        for (int i = 0; i < fullNames.size(); i++) {
            String name = fullNames.get(i);
            JsonElement value = container.get(name);
            if (value == null || !value.isJsonArray()) {
                resolution.diagnostic = JsonTreeDiagnostic
                        .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                "Concept node \"" + name + "\" does not exist"
                                        + (i > 1 ? " under \"" + fullNames.get(i - 1) + "\""
                                                : "") + ".")
                        .path(describe(fullNames, i))
                        .hint("Read the parent branch first to see the existing node names.")
                        .build();
                return resolution;
            }
            JsonArray array = value.getAsJsonArray();
            if (i == fullNames.size() - 1) {
                steps.add(new JsonBranchPath.Step(name, 0));
                resolution.path = JsonBranchPath.ofSteps(
                        steps.toArray(new JsonBranchPath.Step[0]));
                if (i > 1) {
                    resolution.parentName = fullNames.get(i - 1);
                }
                if (parentArray != null) {
                    collectStructuralNames(parentArray, name, resolution.siblingNames);
                }
                return resolution;
            }
            String next = fullNames.get(i + 1);
            int elementIndex = -1;
            for (int e = 0; e < array.size(); e++) {
                JsonElement element = array.get(e);
                if (element.isJsonObject() && element.getAsJsonObject().has(next)) {
                    elementIndex = e;
                    break;
                }
            }
            if (elementIndex < 0) {
                resolution.diagnostic = JsonTreeDiagnostic
                        .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                "Concept node \"" + next + "\" does not exist under \""
                                        + name + "\".")
                        .path(describe(fullNames, i + 1))
                        .hint("Read the parent branch first to see the existing node names.")
                        .build();
                return resolution;
            }
            steps.add(new JsonBranchPath.Step(name, elementIndex));
            parentArray = array;
            container = array.get(elementIndex).getAsJsonObject();
        }
        throw new IllegalStateException("unreachable: loop returns on the last name");
    }

    /** All array-valued property names across the parent array's containers, minus the target. */
    private static void collectStructuralNames(JsonArray parentArray, String except,
            List<String> into) {
        for (JsonElement element : parentArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> property
                    : element.getAsJsonObject().entrySet()) {
                if (property.getValue().isJsonArray() && !property.getKey().equals(except)) {
                    into.add(property.getKey());
                }
            }
        }
    }

    private static String describe(List<String> names, int upToExclusive) {
        StringBuilder sb = new StringBuilder("$");
        for (int i = 0; i <= upToExclusive && i < names.size(); i++) {
            sb.append('.').append(names.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ structure-loss guard

    /**
     * Compare the CURRENT branch with the proposed one on structural names (all levels, root
     * name excluded — renaming the edited node itself is a visible, intentional act). A name
     * that reappears anywhere in the new branch counts as MOVED, not lost — regrouping is
     * exactly what refinement is for.
     */
    private static JsonTreeDiagnostic detectStructureLoss(String document, JsonBranchPath path,
            String proposedBranchJson) {
        JsonBranchCompiler.Result proposed = JsonBranchCompiler.compile(proposedBranchJson);
        if (!proposed.isOk()) {
            return null; // the replacer reports the compile problem with full position info
        }
        JsonBranchExporter.Result current = JsonBranchExporter.exportBranch(document, path);
        if (!current.isOk()) {
            return null; // the replacer reports the path problem
        }
        JsonBranchCompiler.Result old = JsonBranchCompiler.compile(current.getBranchJson());
        if (!old.isOk()) {
            return null;
        }
        Map<String, Integer> before = new LinkedHashMap<String, Integer>();
        Map<String, Integer> after = new LinkedHashMap<String, Integer>();
        countStructuralNames(old.getBranch().getValue(), before);
        countStructuralNames(proposed.getBranch().getValue(), after);
        List<String> lost = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            Integer remaining = after.get(entry.getKey());
            if (remaining == null || remaining < entry.getValue()) {
                lost.add(entry.getKey());
            }
        }
        if (lost.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder(
                "The proposed refinement silently removes existing concept nodes:");
        for (String name : lost) {
            message.append(" \"").append(name).append('"');
        }
        message.append(". A refinement must preserve existing concepts.");
        return JsonTreeDiagnostic.of(JsonTreeErrorCode.STRUCTURE_LOSS_DETECTED,
                message.toString())
                .path(path.describe())
                .hint("Keep every existing node (moving it elsewhere in the branch is fine), "
                        + "or use the explicit remove operation if removal is intended.")
                .build();
    }

    private static void countStructuralNames(JsonArray array, Map<String, Integer> counts) {
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> property
                        : element.getAsJsonObject().entrySet()) {
                    if (property.getValue().isJsonArray()) {
                        Integer count = counts.get(property.getKey());
                        counts.put(property.getKey(), count == null ? 1 : count + 1);
                        countStructuralNames(property.getValue().getAsJsonArray(), counts);
                    }
                }
            } else if (element.isJsonArray()) {
                countStructuralNames(element.getAsJsonArray(), counts);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Prune structural children below {@code depth} to {@code []} — orientation reads only. */
    private static String pruneToDepth(String branchJson, int depth) {
        StrictJsonParseResult parsed = StrictJsonParser.parse(branchJson);
        JsonObject branch = parsed.getElement().getAsJsonObject();
        Map.Entry<String, JsonElement> only = branch.entrySet().iterator().next();
        JsonObject pruned = new JsonObject();
        pruned.add(only.getKey(), pruneArray(only.getValue().getAsJsonArray(), depth));
        return GSON.toJson(pruned);
    }

    private static JsonArray pruneArray(JsonArray array, int depth) {
        JsonArray out = new JsonArray();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                JsonObject copy = new JsonObject();
                for (Map.Entry<String, JsonElement> property
                        : element.getAsJsonObject().entrySet()) {
                    if (property.getValue().isJsonArray()) {
                        copy.add(property.getKey(), depth <= 1 ? new JsonArray()
                                : pruneArray(property.getValue().getAsJsonArray(), depth - 1));
                    } else {
                        copy.add(property.getKey(), property.getValue().deepCopy());
                    }
                }
                out.add(copy);
            } else if (element.isJsonArray()) {
                out.add(depth <= 1 ? new JsonArray()
                        : pruneArray(element.getAsJsonArray(), depth - 1));
            } else {
                out.add(element.deepCopy());
            }
        }
        return out;
    }

    /** Remove the path's final property from its container (and a container left empty). */
    private static JsonTreeDiagnostic removeAt(JsonElement documentRoot, JsonBranchPath path) {
        JsonObject container = documentRoot.getAsJsonObject();
        JsonArray parentArray = null;
        int parentIndex = -1;
        List<JsonBranchPath.Step> steps = path.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            JsonBranchPath.Step step = steps.get(i);
            JsonElement value = container.get(step.getProperty());
            if (value == null || !value.isJsonArray()) {
                return JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "Concept node \"" + step.getProperty() + "\" no longer exists.")
                        .path(path.describePrefix(i + 1)).build();
            }
            if (i == steps.size() - 1) {
                container.remove(step.getProperty());
                if (container.entrySet().isEmpty() && parentArray != null) {
                    parentArray.remove(parentIndex); // an empty container carries nothing
                }
                return null;
            }
            JsonArray array = value.getAsJsonArray();
            if (step.getElementIndex() < 0 || step.getElementIndex() >= array.size()
                    || !array.get(step.getElementIndex()).isJsonObject()) {
                return JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "The path into \"" + step.getProperty() + "\" no longer matches.")
                        .path(path.describePrefix(i + 1)).build();
            }
            parentArray = array;
            parentIndex = step.getElementIndex();
            container = array.get(step.getElementIndex()).getAsJsonObject();
        }
        throw new IllegalStateException("unreachable: loop returns on the last step");
    }

    /** A handle behind the working revision can never be applied again — drop it. */
    private void pruneExpiredHandles() {
        long current = store.workingRevision();
        java.util.Iterator<Map.Entry<String, Handle>> it = handles.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().baseRevision != current) {
                it.remove();
            }
        }
    }

    private static JsonTreeDiagnostic unknownHandle(String handleId) {
        return JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                "Unknown or expired branch handle \"" + handleId + "\".")
                .hint("Read the branch again to obtain a fresh handle.")
                .build();
    }

    private static JsonTreeDiagnostic stale(long expected, long current) {
        return JsonTreeDiagnostic.of(JsonTreeErrorCode.STALE_DOCUMENT_REVISION,
                "The concept changed while this branch was being edited. Expected revision: "
                        + expected + ". Current revision: " + current
                        + ". The branch was not applied.")
                .hint("Read the branch again before applying changes.")
                .build();
    }

    private static ReadResult error(JsonTreeDiagnostic diagnostic) {
        return new ReadResult(null, -1L, null, null, new ArrayList<String>(), false, diagnostic);
    }

    private static EditResult editError(JsonTreeDiagnostic diagnostic) {
        return new EditResult(false, -1L, diagnostic);
    }
}
