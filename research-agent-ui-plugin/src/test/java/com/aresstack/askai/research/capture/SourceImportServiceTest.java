package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * #39 pins (incl. the provenance correction): user imports travel through the SAME acceptance
 * boundary as web captures (dedup + revisions + one source per content), the RAW delivery
 * persists untouched with structured provenance BEFORE the commit (raw and normalized content
 * separately hashed, sourceId bound after), a failed snapshot write FAILS the import instead
 * of silently losing the evidence chain, HTML is extracted mechanically (scripts/styles/
 * object/embed never become evidence, footers survive), and empty input is an honest EMPTY.
 */
public class SourceImportServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final class Fx {
        final CaptureStore captures = new CaptureStore(20, 1000L);
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        final ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
        final SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repo,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        repo.put(record);
                    }
                }, index);
        final ImportedSourceStore importStore;
        final SourceImportService service;

        Fx(File storeDir) {
            importStore = new ImportedSourceStore(storeDir);
            service = new SourceImportService(captures, acceptance, repo, importStore);
        }
    }

    private Fx fx() throws Exception {
        return new Fx(tmp.newFolder("imports"));
    }

    @Test
    public void aTextImportBecomesARegularSourceWithInspectableProvenance() throws Exception {
        Fx fx = fx();
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.TEXT,
                        "Meeting notes", "", "FreeRTOS scheduling notes.\nPreemption rules."),
                "en");
        assertEquals(SourceImportService.Status.IMPORTED, outcome.status);
        assertNotNull(outcome.sourceId);
        ResearchSourceRecord record = fx.repo.get(outcome.sourceId);
        assertEquals("Meeting notes", record.getTitle());
        assertTrue("the SAME boundary indexed it like a web source", fx.index.size() > 0);
        assertTrue("the display note mirrors the provenance",
                record.getComment().contains("User import (text)"));
        assertTrue(record.getComment().contains("extractor=text-passthrough-v1"));
        assertTrue(record.getComment().contains("snapshot=imp-"));
    }

    @Test
    public void theRawDeliveryPersistsUntouchedWithStructuredProvenanceBoundToTheSource()
            throws Exception {
        Fx fx = fx();
        String rawHtml = "<html><head><title>T</title></head>"
                + "<body><p>Alpha evidence text.</p></body></html>";
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.HTML, "",
                        "https://docs.example/x", rawHtml), "en");
        assertEquals(SourceImportService.Status.IMPORTED, outcome.status);

        String rawSha = CaptureStore.sha256(rawHtml);
        String snapshotId = ImportedSourceStore.snapshotIdFor(rawSha);
        ImportedSourceStore.Loaded loaded = fx.importStore.load(snapshotId);
        assertNotNull("the raw snapshot is durable, not a comment", loaded);
        assertEquals("the RAW delivery survives byte-identically", rawHtml,
                loaded.snapshot.rawContent);
        assertEquals("the hash of record is the RAW input", rawSha, loaded.snapshot.rawSha256);
        assertEquals("HTML", loaded.snapshot.kind);
        assertEquals("https://docs.example/x", loaded.snapshot.originUri);
        assertEquals("jsoup-structural-v1", loaded.extraction.extractorId);
        assertEquals("the DERIVED text is separately hashed",
                CaptureStore.sha256(fx.repo.get(outcome.sourceId).getFullText()),
                loaded.extraction.normalizedSha256);
        assertEquals("the acceptance bound its source to the snapshot", outcome.sourceId,
                loaded.sourceId);
        assertEquals(snapshotId, fx.importStore.snapshotIdForSource(outcome.sourceId));
    }

    @Test
    public void aFailedSnapshotWriteFailsTheImportInsteadOfLosingProvenanceSilently()
            throws Exception {
        // A FILE where the store directory should be: mkdirs fails, save throws.
        Fx fx = new Fx(tmp.newFile("not-a-directory"));
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.TEXT,
                        "Notes", "", "Body text that would otherwise import."), "en");
        assertEquals("no provenance, no success", SourceImportService.Status.FAILED,
                outcome.status);
        assertTrue("no phantom source", fx.repo.find(SourceQuery.all()).isEmpty());
        boolean explained = false;
        for (String warning : outcome.warnings) {
            explained |= warning.contains("raw snapshot could not be persisted");
        }
        assertTrue("the failure names its cause", explained);
    }

    @Test
    public void theSameContentImportsOnlyOnceThroughTheCanonicalDedup() throws Exception {
        Fx fx = fx();
        SourceImportService.SourceInput input = new SourceImportService.SourceInput(
                SourceImportService.Kind.TEXT, "Notes", "", "Identical body text.");
        SourceImportService.ImportOutcome first = fx.service.importSource(input, "en");
        SourceImportService.ImportOutcome second = fx.service.importSource(input, "en");
        assertEquals(SourceImportService.Status.IMPORTED, first.status);
        assertEquals("the canonical boundary dedups the re-import",
                SourceImportService.Status.DUPLICATE, second.status);
        assertEquals("one source only", 1, fx.repo.find(SourceQuery.all()).size());
        assertEquals("the duplicate re-import never rebinds the snapshot", first.sourceId,
                fx.importStore.load(ImportedSourceStore.snapshotIdFor(
                        CaptureStore.sha256(input.rawContent))).sourceId);
    }

    @Test
    public void aContentDuplicateSourceStillGetsItsOwnSnapshotBinding() throws Exception {
        Fx fx = fx();
        // Two DIFFERENT raw deliveries (markup differs) from DIFFERENT origins that extract
        // to the SAME normalized text: acceptance creates a second, DUPLICATE-status record.
        String rawA = "<html><head><title>T</title></head>"
                + "<body><p>Identical evidence text.</p></body></html>";
        String rawB = "<html><head><title>T</title></head>"
                + "<body><!-- mirrored copy --><p class='c'>Identical evidence text.</p>"
                + "</body></html>";
        SourceImportService.ImportOutcome first = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.HTML, "",
                        "https://origin-a.example/doc", rawA), "en");
        SourceImportService.ImportOutcome second = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.HTML, "",
                        "https://origin-b.example/mirror", rawB), "en");
        assertEquals(SourceImportService.Status.IMPORTED, first.status);
        assertEquals(SourceImportService.Status.DUPLICATE, second.status);
        assertNotNull("the duplicate is a persisted record of its own", second.sourceId);
        assertFalse(first.sourceId.equals(second.sourceId));
        // BOTH records trace to their own raw delivery — a duplicate's provenance is
        // evidence too, never an orphaned snapshot.
        String snapshotB = ImportedSourceStore.snapshotIdFor(CaptureStore.sha256(rawB));
        assertEquals(second.sourceId, fx.importStore.load(snapshotB).sourceId);
        assertEquals(snapshotB, fx.importStore.snapshotIdForSource(second.sourceId));
        assertEquals(ImportedSourceStore.snapshotIdFor(CaptureStore.sha256(rawA)),
                fx.importStore.snapshotIdForSource(first.sourceId));
    }

    @Test
    public void htmlIsExtractedStructurallyAndChromeNeverBecomesEvidence() throws Exception {
        Fx fx = fx();
        String html = "<html><head><title>Scheduling Guide</title>"
                + "<style>.x{color:red}</style><script>alert('nope');</script></head>"
                + "<body><nav><a href='/'>Home</a></nav>"
                + "<object data='movie.swf'>plugin blob</object>"
                + "<embed src='movie.mov'>"
                + "<h1>Scheduling</h1><p>Preemption decides the running task.</p>"
                + "<ul><li>Priorities order the ready list.</li></ul>"
                + "<footer><p>Published 2001 by RTOS Press.</p></footer>"
                + "</body></html>";
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.HTML, "",
                        "https://docs.example/freertos", html), "en");
        assertEquals(SourceImportService.Status.IMPORTED, outcome.status);
        ResearchSourceRecord record = fx.repo.get(outcome.sourceId);
        assertEquals("the <title> becomes the source title", "Scheduling Guide",
                record.getTitle());
        String text = record.getFullText();
        assertTrue(text.contains("Preemption decides the running task."));
        assertTrue("structure survives as lines",
                text.contains("Scheduling\n") || text.startsWith("Scheduling"));
        assertFalse("scripts are never evidence", text.contains("alert"));
        assertFalse("styles are never evidence", text.contains("color:red"));
        assertFalse("navigation chrome is never evidence", text.contains("Home"));
        assertFalse("object plugin content is never evidence", text.contains("plugin blob"));
        assertTrue("footers SURVIVE — they may carry publication/license/source information",
                text.contains("Published 2001 by RTOS Press."));
        assertTrue("the origin is part of the record", record.getUrl()
                .contains("docs.example"));
        assertTrue(record.getComment().contains("extractor=jsoup-structural-v1"));
    }

    @Test
    public void emptyInputIsAnHonestEmptyNeverAPhantomSource() throws Exception {
        Fx fx = fx();
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.TEXT,
                        "Nothing", "", "   \n  "), "en");
        assertEquals(SourceImportService.Status.EMPTY, outcome.status);
        assertTrue(fx.repo.find(SourceQuery.all()).isEmpty());
    }
}
