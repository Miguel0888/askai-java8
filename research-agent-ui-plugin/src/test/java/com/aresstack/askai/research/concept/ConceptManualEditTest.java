package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The user's manual raw-JSON edit path: strict parse → envelope check → CAS → Gson pretty-print
 * → atomic commit. Invalid input never reaches the store, and every committed working state
 * stays readable in the working history for the editor's rollback.
 */
public class ConceptManualEditTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ConceptBranchService service() throws Exception {
        return new ConceptBranchService(new FileConceptStore(temp.newFolder("concept")));
    }

    @Test
    public void aValidEditIsPrettyPrintedAndCommitted() throws Exception {
        ConceptBranchService service = service();
        ConceptBranchService.EditResult result = service.replaceDocument(
                "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"FreeRTOS\":[]}]}", 0);
        assertTrue(result.isApplied());
        assertEquals(1, result.getNewRevision());
        String stored = service.snapshot().getDocumentJson();
        assertTrue("pretty-printed before persisting", stored.contains("\n"));
        assertTrue(stored, stored.contains("\"FreeRTOS\""));
    }

    @Test
    public void invalidJsonAndABrokenEnvelopeNeverReachTheStore() throws Exception {
        ConceptBranchService service = service();
        ConceptBranchService.EditResult broken =
                service.replaceDocument("{\"concept\":[", 0);
        assertFalse(broken.isApplied());
        assertTrue(broken.getDiagnostic().describeForModel(),
                broken.getDiagnostic().describeForModel().contains("JSON_SYNTAX_ERROR"));

        ConceptBranchService.EditResult noArray =
                service.replaceDocument("{\"concept\":\"not an array\"}", 0);
        assertFalse(noArray.isApplied());
        assertTrue(noArray.getDiagnostic().describeForModel().contains("concept"));

        assertEquals("nothing was committed", 0, service.snapshot().getWorkingRevision());
    }

    @Test
    public void aStaleRevisionIsRejectedInsteadOfOverwritingAConcurrentEdit() throws Exception {
        ConceptBranchService service = service();
        assertTrue(service.replaceDocument(
                "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[]}", 0).isApplied());
        ConceptBranchService.EditResult stale = service.replaceDocument(
                "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"X\":[]}]}", 0);
        assertFalse("the agent edited meanwhile — the user reloads, never overwrites blind",
                stale.isApplied());
    }

    @Test
    public void everyCommittedWorkingStateStaysReadableForTheRollback() throws Exception {
        ConceptBranchService service = service();
        service.replaceDocument("{\"title\":\"\",\"subtitle\":\"\",\"concept\":[]}", 0);
        service.replaceDocument(
                "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"FreeRTOS\":[]}]}", 1);
        assertTrue(service.workingHistoryContent(1).contains("\"concept\": []"));
        assertTrue(service.workingHistoryContent(2).contains("FreeRTOS"));
        assertNull("pre-history revisions answer honestly", service.workingHistoryContent(99));
    }
}
