package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * #39 PDF slice pins: a PDF import travels the SAME neutral port as HTML/text — text via
 * PDFBox in reading order, title from the PDF metadata (file name fallback), the raw PDF
 * bytes byte-identical in the provenance store, and an image-only/blank PDF is an honest
 * EMPTY with a warning, never invented text. No OCR in this slice.
 */
public class PdfImportTest {

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

        Fx() throws Exception {
            importStore = new ImportedSourceStore(tmp.newFolder("imports"));
            service = new SourceImportService(captures, acceptance, repo, importStore);
        }
    }

    /** A real one-page PDF built with the SAME library generation the extractor uses. */
    private static byte[] pdfWithText(String metadataTitle, String bodyText) throws Exception {
        PDDocument document = new PDDocument();
        try {
            PDPage page = new PDPage();
            document.addPage(page);
            PDPageContentStream content = new PDPageContentStream(document, page);
            content.beginText();
            content.setFont(PDType1Font.HELVETICA, 12);
            content.newLineAtOffset(50, 700);
            content.showText(bodyText);
            content.endText();
            content.close();
            if (metadataTitle != null) {
                document.getDocumentInformation().setTitle(metadataTitle);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } finally {
            document.close();
        }
    }

    private static byte[] blankPdf() throws Exception {
        PDDocument document = new PDDocument();
        try {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } finally {
            document.close();
        }
    }

    @Test
    public void aPdfImportsThroughTheSamePortWithMetadataTitleAndByteIdenticalRaw()
            throws Exception {
        Fx fx = new Fx();
        byte[] pdf = pdfWithText("Scheduling Guide", "FreeRTOS preemption evidence from PDF.");
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.PDF, "",
                        "C:\\docs\\freertos-guide.pdf", pdf), "en");
        assertEquals(SourceImportService.Status.IMPORTED, outcome.status);
        ResearchSourceRecord record = fx.repo.get(outcome.sourceId);
        assertEquals("the PDF metadata title wins", "Scheduling Guide", record.getTitle());
        assertTrue("the stripped text is the evidence",
                record.getFullText().contains("FreeRTOS preemption evidence from PDF."));
        String rawSha = CaptureStore.sha256(pdf);
        ImportedSourceStore.Loaded loaded = fx.importStore.load(
                ImportedSourceStore.snapshotIdFor(rawSha, "C:\\docs\\freertos-guide.pdf"));
        assertNotNull(loaded);
        assertTrue("the raw PDF survives byte-identically — no charset round trip",
                Arrays.equals(pdf, loaded.snapshot.rawBytes));
        assertEquals(rawSha, loaded.snapshot.rawSha256);
        assertEquals("pdfbox-text-v1", loaded.extraction.extractorId);
        assertEquals(outcome.sourceId, loaded.sourceId);
        boolean pagesNoted = false;
        for (String warning : loaded.extraction.warnings) {
            pagesNoted |= warning.equals("pages=1");
        }
        assertTrue("the page count is part of the extraction record", pagesNoted);
    }

    @Test
    public void aPdfWithoutMetadataTitleFallsBackToTheDeliveredFileName() throws Exception {
        Fx fx = new Fx();
        byte[] pdf = pdfWithText(null, "Body without any metadata title.");
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.PDF, "",
                        "C:\\downloads\\meeting-notes.pdf", pdf), "en");
        assertEquals(SourceImportService.Status.IMPORTED, outcome.status);
        assertEquals("meeting-notes.pdf", fx.repo.get(outcome.sourceId).getTitle());
    }

    @Test
    public void anImageOnlyOrBlankPdfIsAnHonestEmptyButItsRawDeliverySurvives() throws Exception {
        Fx fx = new Fx();
        byte[] scan = blankPdf();
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.PDF,
                        "Scan", "scan.pdf", scan), "en");
        assertEquals(SourceImportService.Status.EMPTY, outcome.status);
        assertTrue("no phantom source", fx.repo.find(SourceQuery.all()).isEmpty());
        boolean explained = false;
        for (String warning : outcome.warnings) {
            explained |= warning.contains("no extractable text");
        }
        assertTrue("the emptiness names its cause (no OCR in this slice)", explained);
        // The scan's bytes are exactly what a later OCR slice needs — EMPTY never means
        // the delivery was thrown away.
        ImportedSourceStore.Loaded loaded = fx.importStore.load(
                ImportedSourceStore.snapshotIdFor(CaptureStore.sha256(scan), "scan.pdf"));
        assertNotNull("the raw delivery is preserved despite EMPTY", loaded);
        assertTrue(Arrays.equals(scan, loaded.snapshot.rawBytes));
        assertEquals("no source was accepted, so none is bound", "", loaded.sourceId);
        assertEquals("no derived text, no derived hash", "",
                loaded.extraction.normalizedSha256);
    }

    @Test
    public void garbageBytesAreAnHonestEmptyWithAParseWarningNeverACrash() throws Exception {
        Fx fx = new Fx();
        byte[] garbage = new byte[] {0x25, 0x50, 0x44, 0x46, 0x00, (byte) 0xFF, 0x13};
        SourceImportService.ImportOutcome outcome = fx.service.importSource(
                new SourceImportService.SourceInput(SourceImportService.Kind.PDF,
                        "Broken", "broken.pdf", garbage), "en");
        assertEquals(SourceImportService.Status.EMPTY, outcome.status);
        boolean explained = false;
        for (String warning : outcome.warnings) {
            explained |= warning.contains("could not be parsed");
        }
        assertTrue(explained);
        // Even an unparseable delivery keeps its raw snapshot WITH the parse warning —
        // the evidence of what was delivered never depends on it being readable.
        ImportedSourceStore.Loaded loaded = fx.importStore.load(
                ImportedSourceStore.snapshotIdFor(CaptureStore.sha256(garbage), "broken.pdf"));
        assertNotNull(loaded);
        assertTrue(Arrays.equals(garbage, loaded.snapshot.rawBytes));
        boolean recorded = false;
        for (String warning : loaded.extraction.warnings) {
            recorded |= warning.contains("could not be parsed");
        }
        assertTrue("the parse warning is part of the persisted extraction record", recorded);
    }
}
