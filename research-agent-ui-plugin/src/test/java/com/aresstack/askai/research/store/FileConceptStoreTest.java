package com.aresstack.askai.research.store;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The concept store's two revision notions: the WORKING revision bumps on every real
 * micro-edit (and only then — dedupe), APPROVED revisions are immutable snapshots created only
 * by the explicit user gate. Everything must survive a reload from disk.
 */
public class FileConceptStoreTest {

    private static FileConceptStore fresh() throws Exception {
        File dir = Files.createTempDirectory("askai-concept-store").toFile();
        return new FileConceptStore(new File(dir, "concept"));
    }

    @Test
    public void aFreshConceptIsTheEmptyEnvelopeAtRevisionZero() throws Exception {
        FileConceptStore store = fresh();
        assertEquals(0L, store.workingRevision());
        assertEquals(FileConceptStore.EMPTY_DOCUMENT, store.effectiveContent());
        assertNull(store.latestApprovedContent());
    }

    @Test
    public void everySuccessfulMicroEditBumpsTheWorkingRevision() throws Exception {
        FileConceptStore store = fresh();
        assertEquals(1L, store.commitWorking("{\"title\":\"RTOS\",\"content\":[]}", 1000L));
        assertEquals(2L, store.commitWorking(
                "{\"title\":\"RTOS\",\"content\":[{\"Tasks\":[]}]}", 2000L));
        assertEquals("{\"title\":\"RTOS\",\"content\":[{\"Tasks\":[]}]}",
                store.effectiveContent());
    }

    @Test
    public void anIdenticalCommitDoesNotBumpTheRevision() throws Exception {
        FileConceptStore store = fresh();
        store.commitWorking("{\"content\":[]}", 1000L);
        assertEquals("a no-op edit must not invalidate everyone's branch handles",
                1L, store.commitWorking("{\"content\":[]}", 2000L));
    }

    @Test
    public void approvalFreezesImmutableNumberedRevisions() throws Exception {
        FileConceptStore store = fresh();
        store.commitWorking("{\"content\":[{\"Tasks\":[]}]}", 1000L);
        FileConceptStore.Approval first = store.approveCurrent(1500L);
        assertTrue(first.isNewRevision());
        assertEquals(1, first.getRevisionNumber());
        // Unchanged working state → ALREADY_CURRENT, no second snapshot.
        FileConceptStore.Approval again = store.approveCurrent(1600L);
        assertFalse(again.isNewRevision());
        assertEquals(1, again.getRevisionNumber());
        // The working copy moves on; the approved snapshot stays frozen.
        store.commitWorking("{\"content\":[{\"Tasks\":[]},{\"Queues\":[]}]}", 2000L);
        assertEquals("{\"content\":[{\"Tasks\":[]}]}", store.latestApprovedContent());
        FileConceptStore.Approval second = store.approveCurrent(2500L);
        assertTrue(second.isNewRevision());
        assertEquals(2, second.getRevisionNumber());
    }

    @Test
    public void everythingSurvivesAReloadFromDisk() throws Exception {
        File dir = Files.createTempDirectory("askai-concept-reload").toFile();
        File conceptDir = new File(dir, "concept");
        FileConceptStore store = new FileConceptStore(conceptDir);
        store.commitWorking("{\"content\":[{\"Tasks\":[]}]}", 1000L);
        store.commitWorking("{\"content\":[{\"Tasks\":[]},{\"Queues\":[]}]}", 2000L);
        store.approveCurrent(3000L);

        FileConceptStore reloaded = new FileConceptStore(conceptDir);
        assertEquals(2L, reloaded.workingRevision());
        assertEquals("{\"content\":[{\"Tasks\":[]},{\"Queues\":[]}]}",
                reloaded.effectiveContent());
        assertEquals(1, reloaded.latestApprovedNumber());
    }
}
