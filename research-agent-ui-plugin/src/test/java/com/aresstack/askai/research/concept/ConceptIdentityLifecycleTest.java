package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The ID-sidecar lifecycles of the ratified V3 design, pinned along the acceptance gates:
 * migration, restart, per-operation identity, semantic no-ops, the raw-save epoch cut, restore
 * within and across epochs, pre-sidecar degradation, crash/orphan behaviour, self-heal, backup
 * recovery, fail-closed, and the ID-free user JSON.
 */
public class ConceptIdentityLifecycleTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File dir;

    private FileConceptStore store() throws Exception {
        if (dir == null) {
            dir = temp.newFolder("concept");
        }
        return new FileConceptStore(dir);
    }

    private static List<String> drain(ConceptBranchService service) {
        final List<String> lines = new ArrayList<String>();
        service.setDiagnosticSink(new ConceptBranchService.DiagnosticSink() {
            public void line(String line) {
                lines.add(line);
            }
        });
        return lines;
    }

    // ------------------------------------------------------------------ gate 1: migration

    @Test
    public void anExistingSessionIsMigratedOnceWithoutTouchingDocumentOrRevision() throws Exception {
        FileConceptStore legacy = store();
        legacy.commitWorking("{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"A\":[]}]}", 1L);
        String document = legacy.effectiveContent();

        ConceptBranchService service = new ConceptBranchService(store());
        List<String> diagnostics = drain(service);
        assertEquals("the revision stays", 1L, service.snapshot().getWorkingRevision());
        assertEquals("the user JSON stays byte-identical", document,
                service.snapshot().getDocumentJson());
        assertNotNull(service.currentEpoch());
        assertNotNull(service.nodeIdAtPath(Collections.singletonList("A")));
        assertTrue(diagnostics.toString(), diagnostics.toString().contains("MIGRATED"));

        // Idempotent: the next load finds the adopted identity and mints nothing new.
        ConceptBranchService again = new ConceptBranchService(store());
        assertEquals(service.currentEpoch(), again.currentEpoch());
        assertFalse(drain(again).toString().contains("MIGRATED"));
    }

    // ------------------------------------------------------------------ gate 2: restart

    @Test
    public void aRestartKeepsTheEpochAndEveryNodeId() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        assertTrue(service.addNode(Collections.<String>emptyList(), "Buch").isApplied());
        assertTrue(service.addNode(Collections.singletonList("Buch"), "Setup").isApplied());
        String epoch = service.currentEpoch();
        String setupId = service.nodeIdAtPath(Arrays.asList("Buch", "Setup"));

        ConceptBranchService restarted = new ConceptBranchService(store());
        assertEquals(epoch, restarted.currentEpoch());
        assertEquals(setupId, restarted.nodeIdAtPath(Arrays.asList("Buch", "Setup")));
    }

    // ------------------------------------------------------------------ gate 3: add / rename

    @Test
    public void addMintsAFreshUuidAndRenameKeepsTheExistingOne() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "Buch");
        service.addNode(Collections.singletonList("Buch"), "Setup");
        String setupId = service.nodeIdAtPath(Arrays.asList("Buch", "Setup"));
        assertNotNull(setupId);

        assertTrue(service.renameNode(Arrays.asList("Buch", "Setup"),
                "Entwicklungsumgebung").isApplied());
        assertEquals("a rename never changes identity", setupId,
                service.nodeIdAtPath(Arrays.asList("Buch", "Entwicklungsumgebung")));
        assertEquals("the id resolves to the CURRENT path",
                Arrays.asList("Buch", "Entwicklungsumgebung"), service.pathOfNodeId(setupId));

        service.addNode(Collections.singletonList("Buch"), "Praxis");
        String praxisId = service.nodeIdAtPath(Arrays.asList("Buch", "Praxis"));
        assertNotNull(praxisId);
        assertNotEquals(setupId, praxisId);
    }

    // ------------------------------------------------------------------ gate 4: semantic no-op

    @Test
    public void aNoOpCreatesNoRevisionAndKeepsTheLeafIds() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "Setup");
        service.addNode(Collections.singletonList("Setup"), "Arduino");
        long revision = service.snapshot().getWorkingRevision();
        String leafId = service.nodeIdAtPath(Arrays.asList("Setup", "Arduino"));

        ConceptBranchService.EditResult noOp = service.rewriteTerminalBranch(
                Collections.singletonList("Setup"), Collections.singletonList("Arduino"));
        assertTrue(noOp.isApplied());
        assertEquals("an unchanged document never commits", revision, noOp.getNewRevision());
        assertEquals("no-op UUID minting is discarded, never committed", leafId,
                service.nodeIdAtPath(Arrays.asList("Setup", "Arduino")));
    }

    // ------------------------------------------------------------------ gate 5: dirty raw save

    @Test
    public void aDirtyRawSaveWithIdenticalJsonStillCutsANewEpoch() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "Buch");
        final List<String> boundaries = new ArrayList<String>();
        service.setLifecycleListener(new ConceptBranchService.LifecycleListener() {
            public void onTransientBoundary(String reason, String newEpoch) {
                boundaries.add(reason);
            }
        });
        String pretty = firstSave(service);
        long revision = service.snapshot().getWorkingRevision();
        String epoch = service.currentEpoch();
        String buchId = service.nodeIdAtPath(Collections.singletonList("Buch"));

        ConceptBranchService.EditResult saved = service.replaceDocument(pretty, revision);
        assertTrue(saved.isApplied());
        assertEquals("identical JSON, but a dirty save is never a no-op",
                revision + 1, saved.getNewRevision());
        assertNotEquals("new epoch", epoch, service.currentEpoch());
        assertNotEquals("fresh UUIDs for every node", buchId,
                service.nodeIdAtPath(Collections.singletonList("Buch")));
        assertTrue(boundaries.contains("raw-save"));
    }

    /** One raw save to get the canonical pretty-printed head text. */
    private static String firstSave(ConceptBranchService service) {
        ConceptBranchService.DocumentSnapshot snapshot = service.snapshot();
        assertTrue(service.replaceDocument(snapshot.getDocumentJson(),
                snapshot.getWorkingRevision()).isApplied());
        return service.snapshot().getDocumentJson();
    }

    // ------------------------------------------------------------------ gate 9: restore (same epoch)

    @Test
    public void restoreWithinAnEpochBringsTheHistoricalIdsBackAsANewHead() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "A");
        long revisionA = service.snapshot().getWorkingRevision();
        String idA = service.nodeIdAtPath(Collections.singletonList("A"));
        String epoch = service.currentEpoch();
        String historicalDoc = service.snapshot().getDocumentJson();
        service.addNode(Collections.<String>emptyList(), "B");

        final List<String> boundaries = new ArrayList<String>();
        service.setLifecycleListener(new ConceptBranchService.LifecycleListener() {
            public void onTransientBoundary(String reason, String newEpoch) {
                boundaries.add(reason + ":" + newEpoch);
            }
        });
        ConceptBranchService.EditResult restored = service.restoreRevision(revisionA);
        assertTrue(restored.isApplied());
        assertEquals("re-stamped as the NEW head", revisionA + 2, restored.getNewRevision());
        assertEquals(epoch, service.currentEpoch());
        assertEquals("the historical UUID lives again", idA,
                service.nodeIdAtPath(Collections.singletonList("A")));
        assertNull("B is gone with the restore",
                service.nodeIdAtPath(Collections.singletonList("B")));
        assertTrue("a restore is ALWAYS a transient boundary (candidate/open model)",
                boundaries.get(0).startsWith("restore:"));
        assertEquals("the historical pair stays byte-identical", historicalDoc,
                service.workingHistoryContent(revisionA));
    }

    // ------------------------------------------------------------------ gate 10: restore across epochs

    @Test
    public void restoreAcrossAnEpochBoundaryRevivesTheHistoricalEpoch() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "Alt");
        String oldEpoch = service.currentEpoch();
        String oldId = service.nodeIdAtPath(Collections.singletonList("Alt"));
        long oldRevision = service.snapshot().getWorkingRevision();

        // The raw save cuts epoch 2 …
        ConceptBranchService.DocumentSnapshot head = service.snapshot();
        assertTrue(service.replaceDocument(head.getDocumentJson(),
                head.getWorkingRevision()).isApplied());
        assertNotEquals(oldEpoch, service.currentEpoch());

        // … and the restore of the epoch-1 revision makes epoch 1 CURRENT again.
        assertTrue(service.restoreRevision(oldRevision).isApplied());
        assertEquals(oldEpoch, service.currentEpoch());
        assertEquals(oldId, service.nodeIdAtPath(Collections.singletonList("Alt")));
    }

    // ------------------------------------------------------------------ gate 11: pre-sidecar restore

    @Test
    public void restoringAPreIdentityRevisionExplicitlyMintsANewEpoch() throws Exception {
        FileConceptStore legacy = store();
        legacy.commitWorking("{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"Alt\":[]}]}", 1L);
        legacy.commitWorking("{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"Neu\":[]}]}", 2L);

        ConceptBranchService service = new ConceptBranchService(store()); // migrates at rev 2
        String migratedEpoch = service.currentEpoch();
        List<String> diagnostics = drain(service);
        assertTrue(service.restoreRevision(1L).isApplied());
        assertNotEquals("no silent reconstruction — an explicit fresh epoch",
                migratedEpoch, service.currentEpoch());
        assertTrue(diagnostics.toString().contains("DEGRADED"));
    }

    // ------------------------------------------------------------------ gates 12+13: crash & self-heal

    @Test
    public void orphansAreNeverAdoptedAndBrokenWorkingFilesSelfHeal() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "A");
        service.addNode(Collections.<String>emptyList(), "B");
        long confirmed = service.snapshot().getWorkingRevision();
        String confirmedDoc = service.snapshot().getDocumentJson();
        String epoch = service.currentEpoch();

        // Crash between history write and manifest swap: rev N+1 files exist, manifest says N.
        write(new File(new File(dir, "working-history"), (confirmed + 1) + ".json"),
                "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"ORPHAN\":[]}]}");
        write(new File(new File(dir, "identity-history"), (confirmed + 1) + ".json"),
                "{\"formatVersion\":1,\"epoch\":\"e-orphan\",\"revision\":" + (confirmed + 1)
                        + ",\"documentHash\":\"x\",\"nodes\":[{\"i\":\"o\",\"c\":[]}]}");
        // Crash between working publication and manifest swap: working.json holds unconfirmed.
        write(new File(dir, "working.json"),
                "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"UNCONFIRMED\":[]}]}");

        ConceptBranchService recovered = new ConceptBranchService(store());
        List<String> diagnostics = drain(recovered);
        assertEquals("only the manifest-confirmed revision is visible", confirmed,
                recovered.snapshot().getWorkingRevision());
        assertEquals("the unconfirmed document was never adopted", confirmedDoc,
                recovered.snapshot().getDocumentJson());
        assertEquals("the epoch survived the crash", epoch, recovered.currentEpoch());
        assertTrue(diagnostics.toString().contains("orphan rev " + (confirmed + 1)));
        assertTrue(diagnostics.toString().contains("SELF-HEALED"));
        // The next commit atomically replaces the orphan.
        assertTrue(recovered.addNode(Collections.<String>emptyList(), "C").isApplied());
        assertTrue(recovered.snapshot().getDocumentJson().contains("\"C\""));
        assertFalse(recovered.workingHistoryContent(confirmed + 1).contains("ORPHAN"));
    }

    // ------------------------------------------------------------------ gate 14: backup & fail-closed

    @Test
    public void aCorruptManifestUsesTheValidatedBackupOrLocksTheStore() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "A");
        service.addNode(Collections.<String>emptyList(), "B"); // second commit → backup exists
        long headRevision = service.snapshot().getWorkingRevision();

        write(new File(dir, "working.properties"), "garbage ¬ not a manifest ==");
        ConceptBranchService recovered = new ConceptBranchService(store());
        List<String> diagnostics = drain(recovered);
        assertFalse(recovered.isFailClosed());
        assertEquals("the backup names the PREVIOUS confirmed pair — the last commit is lost "
                + "by design, never guessed from orphans", headRevision - 1,
                recovered.snapshot().getWorkingRevision());
        assertTrue(diagnostics.toString().contains("RESTORED FROM BACKUP"));
        assertTrue("the store writes again after recovery",
                recovered.addNode(Collections.<String>emptyList(), "C").isApplied());

        // Manifest AND backup invalid → fail-closed: write-protected, preview marked.
        write(new File(dir, "working.properties"), "garbage");
        write(new File(dir, "working.properties.prev"), "also garbage");
        ConceptBranchService locked = new ConceptBranchService(store());
        assertTrue(locked.isFailClosed());
        assertNotNull("the snapshot is an explicit recovery preview",
                locked.snapshot().getRecoveryNotice());
        assertFalse("writes are refused",
                locked.addNode(Collections.<String>emptyList(), "X").isApplied());
        assertFalse(locked.replaceDocument("{\"concept\":[]}", 0L).isApplied());
        assertFalse(locked.restoreRevision(1L).isApplied());
        assertFalse("model reads never ground on an unconfirmed preview",
                locked.readBranch(Collections.<String>emptyList(), 0).isOk());
    }

    // ------------------------------------------------------------------ gate 15: ID-free user JSON

    @Test
    public void theVisibleUserJsonNeverCarriesIdsOrSidecarMetadata() throws Exception {
        ConceptBranchService service = new ConceptBranchService(store());
        service.addNode(Collections.<String>emptyList(), "Buch");
        service.renameNode(Collections.singletonList("Buch"), "Werk");
        String document = service.snapshot().getDocumentJson();
        assertFalse(document.contains("formatVersion"));
        assertFalse(document.contains("epoch"));
        assertFalse(document.contains(service.currentEpoch()));
        assertFalse(document.contains(service.nodeIdAtPath(
                Collections.singletonList("Werk"))));
    }

    private static void write(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), "UTF-8");
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}
