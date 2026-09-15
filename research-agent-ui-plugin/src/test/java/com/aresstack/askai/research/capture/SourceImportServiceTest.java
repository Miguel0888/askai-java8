package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * #39 slices 2+4 pins: user imports travel through the SAME acceptance boundary as web
 * captures (dedup + revisions + one source per content), HTML is extracted mechanically and
 * structure-preserving (scripts/styles never become evidence), provenance is inspectable on
 * the source, and empty input is an honest EMPTY, never a phantom source.
 */
public class SourceImportServiceTest {

    private static final class Fx {
        final CaptureStore captures = new CaptureStore(20, 1000L);
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        final ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
        final SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repo,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        repo.put(record);
                    }
                }, index);
        final SourceImportService service =
                new SourceImportService(captures, acceptance, repo);
    }

    @Test
    public void aTextImportBecomesARegularSourceWithInspectableProvenance() {
        Fx fx = new Fx();
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.TEXT,
                        "Meeting notes", "", "FreeRTOS scheduling notes.\nPreemption rules."),
                "en");
        assertEquals(SourceImportService.Status.IMPORTED, outcome.status);
        assertNotNull(outcome.sourceId);
        ResearchSourceRecord record = fx.repo.get(outcome.sourceId);
        assertEquals("Meeting notes", record.getTitle());
        assertTrue("the SAME boundary indexed it like a web source", fx.index.size() > 0);
        assertTrue("provenance is inspectable",
                record.getComment().contains("User import (text)"));
        assertTrue(record.getComment().contains("extractor=text-passthrough-v1"));
        assertTrue(record.getComment().contains("sha256="));
    }

    @Test
    public void theSameContentImportsOnlyOnceThroughTheCanonicalDedup() {
        Fx fx = new Fx();
        SourceImportService.SourceInput input = new SourceImportService.SourceInput(
                SourceImportService.Kind.TEXT, "Notes", "", "Identical body text.");
        SourceImportService.ImportOutcome first = fx.service.importSource(input, "en");
        SourceImportService.ImportOutcome second = fx.service.importSource(input, "en");
        assertEquals(SourceImportService.Status.IMPORTED, first.status);
        assertEquals("the canonical boundary dedups the re-import",
                SourceImportService.Status.DUPLICATE, second.status);
        assertEquals("one source only", 1, fx.repo.find(SourceQuery.all()).size());
    }

    @Test
    public void htmlIsExtractedStructurallyAndChromeNeverBecomesEvidence() {
        Fx fx = new Fx();
        String html = "<html><head><title>Scheduling Guide</title>"
                + "<style>.x{color:red}</style><script>alert('nope');</script></head>"
                + "<body><nav><a href='/'>Home</a></nav>"
                + "<h1>Scheduling</h1><p>Preemption decides the running task.</p>"
                + "<ul><li>Priorities order the ready list.</li></ul>"
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
        assertTrue("the origin is part of the record", record.getUrl()
                .contains("docs.example"));
        assertTrue(record.getComment().contains("extractor=jsoup-structural-v1"));
    }

    @Test
    public void emptyInputIsAnHonestEmptyNeverAPhantomSource() {
        Fx fx = new Fx();
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.TEXT,
                        "Nothing", "", "   \n  "), "en");
        assertEquals(SourceImportService.Status.EMPTY, outcome.status);
        assertTrue(fx.repo.find(SourceQuery.all()).isEmpty());
    }
}
