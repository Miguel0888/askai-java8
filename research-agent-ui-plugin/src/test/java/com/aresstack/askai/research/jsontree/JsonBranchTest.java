package com.aresstack.askai.research.jsontree;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Compile-before-swap, end to end: a model branch is strictly compiled, grafted into a CANDIDATE
 * copy, the candidate fully re-validated, and only then committed as a new revision. The core
 * invariant: after EVERY failed parse/compile/graft/validation step the previously valid document
 * is untouched — no result carries a document, and the old text still round-trips identically.
 */
public class JsonBranchTest {

    /** The working document of most tests; revision 27 by convention of the examples. */
    private static final String DOC = "{\"FreeRTOS\":[{\"Tasks\":[],"
            + "\"Synchronisation\":[{\"Mutex\":[],\"Semaphore\":[]}],"
            + "\"importance\":0.8}],\"settings\":{\"profiles\":[]}}";
    private static final long REV = 27L;

    private static final JsonBranchPath SYNC_PATH =
            JsonBranchPath.of("FreeRTOS", "Synchronisation");

    // ------------------------------------------------------------------ export

    @Test
    public void aBranchExportsExactlyTheAddressedSubtree() {
        JsonBranchExporter.Result result = JsonBranchExporter.exportBranch(DOC, SYNC_PATH);
        assertTrue(result.isOk());
        assertEquals("{\"Synchronisation\":[{\"Mutex\":[],\"Semaphore\":[]}]}",
                result.getBranchJson());
    }

    @Test
    public void theStructuralExportDropsEveryNonArrayProperty() {
        JsonBranchExporter.Result result =
                JsonBranchExporter.exportStructuralBranch(DOC, JsonBranchPath.of("FreeRTOS"));
        assertTrue(result.isOk());
        assertEquals("the mindmap tool sees pure array structure — no importance, no settings",
                "{\"FreeRTOS\":[{\"Tasks\":[],\"Synchronisation\":[{\"Mutex\":[],"
                        + "\"Semaphore\":[]}]}]}",
                result.getBranchJson());
    }

    @Test
    public void exportingThroughAnObjectLeafFails() {
        // settings is an ObjectLeaf: the path ends there, profiles must be unreachable.
        JsonBranchExporter.Result result = JsonBranchExporter.exportBranch(DOC,
                JsonBranchPath.of("settings", "profiles"));
        assertFalse(result.isOk());
        assertEquals(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND, result.getDiagnostic().getCode());
        assertEquals("$.settings", result.getDiagnostic().getPath());
    }

    // ------------------------------------------------------------------ compile

    @Test
    public void aValidBranchCompiles() {
        JsonBranchCompiler.Result result = JsonBranchCompiler.compile(
                "{\"Synchronisation\": [ {\"Mutex\": [], \"Semaphore\": []} ]}");
        assertTrue(result.isOk());
        assertEquals("Synchronisation", result.getBranch().getName());
    }

    @Test
    public void aBranchWithTwoPropertiesIsNoValidRoot() {
        JsonBranchCompiler.Result result =
                JsonBranchCompiler.compile("{\"A\": [], \"B\": []}");
        assertFalse(result.isOk());
        assertEquals(JsonTreeErrorCode.INVALID_BRANCH_ROOT, result.getDiagnostic().getCode());
    }

    @Test
    public void aBranchWhoseValueIsNoArrayIsNoValidRoot() {
        JsonBranchCompiler.Result result =
                JsonBranchCompiler.compile("{\"A\": {\"nested\": []}}");
        assertFalse(result.isOk());
        assertEquals(JsonTreeErrorCode.INVALID_BRANCH_ROOT, result.getDiagnostic().getCode());
        assertTrue("the hint teaches the required shape",
                result.getDiagnostic().getHint().contains("{\"Name\": [ ... ]}"));
    }

    @Test
    public void aBareArrayIsNoValidRoot() {
        assertEquals(JsonTreeErrorCode.INVALID_BRANCH_ROOT,
                JsonBranchCompiler.compile("[1, 2]").getDiagnostic().getCode());
    }

    // ------------------------------------------------------------------ replace (happy path)

    @Test
    public void aValidBranchIsGraftedAndCommittedAsANewRevision() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, REV, new BranchEditRequest(REV,
                SYNC_PATH, "{\"Synchronisation\": [ {\"Mutex\": [], \"Semaphore\": [], "
                        + "\"Event Groups\": [], \"Critical Sections\": []} ]}"));
        assertTrue(result.isCommitted());
        assertEquals(REV + 1, result.getNewRevision());
        String doc = result.getDocumentJson();
        assertTrue(doc.contains("\"Event Groups\":[]"));
        assertTrue("siblings outside the branch survive", doc.contains("\"importance\":0.8"));
        assertTrue("untouched subtrees survive", doc.contains("\"profiles\":[]"));
        // The candidate went through full re-validation — it must project cleanly.
        JsonTreeParseResult reparsed = JsonTreeParser.parse(doc);
        assertTrue(reparsed.isOk());
        StructuralForest forest = StructuralTreeExtractor.extract(reparsed.getTree());
        assertEquals("FreeRTOS", forest.getRoots().get(0).getName());
    }

    @Test
    public void aRenamedBranchRootReplacesTheOldPropertyAtItsPosition() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, REV, new BranchEditRequest(REV,
                SYNC_PATH, "{\"Sync-Primitives\": [ {\"Mutex\": []} ]}"));
        assertTrue(result.isCommitted());
        String doc = result.getDocumentJson();
        assertFalse("the old name is gone", doc.contains("Synchronisation"));
        assertTrue("the renamed root sits between its old neighbours (position preserved)",
                doc.indexOf("\"Tasks\"") < doc.indexOf("\"Sync-Primitives\"")
                        && doc.indexOf("\"Sync-Primitives\"") < doc.indexOf("\"importance\""));
    }

    // ------------------------------------------------------------------ replace (rejections)

    @Test
    public void aStaleRevisionIsRejectedAndNothingIsApplied() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, 28L, new BranchEditRequest(REV,
                SYNC_PATH, "{\"Synchronisation\": []}"));
        assertRejected(result, JsonTreeErrorCode.STALE_DOCUMENT_REVISION);
        assertTrue(result.getDiagnostic().getMessage().contains("Expected revision: 27"));
        assertTrue(result.getDiagnostic().getMessage().contains("Current revision: 28"));
    }

    @Test
    public void aSyntacticallyBrokenBranchIsRejectedWithTheStrictDiagnostic() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, REV, new BranchEditRequest(REV,
                SYNC_PATH, "{\"Synchronisation\": [ {\"Mutex\": [] \"Semaphore\": []} ]}"));
        assertRejected(result, JsonTreeErrorCode.JSON_SYNTAX_ERROR);
        assertTrue("position information reaches the model",
                result.getDiagnostic().getLine() > 0);
    }

    @Test
    public void aWrongTargetPathIsRejected() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, REV, new BranchEditRequest(REV,
                JsonBranchPath.of("FreeRTOS", "DoesNotExist"),
                "{\"DoesNotExist\": []}"));
        assertRejected(result, JsonTreeErrorCode.TARGET_NODE_NOT_FOUND);
        assertEquals("$.FreeRTOS[0].DoesNotExist", result.getDiagnostic().getPath());
    }

    @Test
    public void anOutOfRangeElementIndexIsRejected() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, REV, new BranchEditRequest(REV,
                JsonBranchPath.ofSteps(new JsonBranchPath.Step("FreeRTOS", 5),
                        new JsonBranchPath.Step("Synchronisation", 0)),
                "{\"Synchronisation\": []}"));
        assertRejected(result, JsonTreeErrorCode.TARGET_NODE_NOT_FOUND);
    }

    @Test
    public void aRenameCollidingWithASiblingIsRefused() {
        BranchEditResult result = JsonBranchReplacer.apply(DOC, REV, new BranchEditRequest(REV,
                SYNC_PATH, "{\"Tasks\": []}"));
        assertRejected(result, JsonTreeErrorCode.BRANCH_GRAFT_FAILED);
    }

    /** THE invariant: a failed edit leaves the valid state byte-identical and fully usable. */
    @Test
    public void everyFailurePathLeavesTheValidDocumentUntouched() {
        String before = DOC;
        BranchEditRequest[] failing = {
                new BranchEditRequest(REV - 1, SYNC_PATH, "{\"Synchronisation\": []}"),
                new BranchEditRequest(REV, SYNC_PATH, "{broken"),
                new BranchEditRequest(REV, SYNC_PATH, "{\"A\": [], \"B\": []}"),
                new BranchEditRequest(REV, JsonBranchPath.of("nope"), "{\"nope\": []}"),
                new BranchEditRequest(REV, SYNC_PATH, "{\"Tasks\": []}"),
        };
        for (BranchEditRequest request : failing) {
            BranchEditResult result = JsonBranchReplacer.apply(before, REV, request);
            assertFalse(result.isCommitted());
            assertNull("a rejected edit must never leak a document", result.getDocumentJson());
            assertEquals("the valid document is byte-identical after the failure",
                    DOC, before);
            // …and still fully editable: the SAME valid edit works on the untouched state.
            assertTrue(JsonBranchReplacer.apply(before, REV, new BranchEditRequest(REV,
                    SYNC_PATH, "{\"Synchronisation\": [ {\"Mutex\": []} ]}")).isCommitted());
        }
    }

    private static void assertRejected(BranchEditResult result, JsonTreeErrorCode code) {
        assertFalse(result.isCommitted());
        assertEquals(code, result.getDiagnostic().getCode());
        assertNull(result.getDocumentJson());
    }
}
