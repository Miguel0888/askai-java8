package com.aresstack.askai.research.capture;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #39 — MECHANICAL PDF text extraction for user imports (PDFBox 2.0.x, the Java-8 line):
 * {@code PDDocument} loads from the delivered bytes, {@code PDFTextStripper} with
 * position-sorting yields reading-order text, the title comes from the PDF metadata.
 * Deterministic, no model, no OCR in this slice — an image-only or encrypted document
 * reports an honest warning and empty text instead of invented content; the raw PDF stays
 * byte-identical in the {@link ImportedSourceStore} regardless.
 */
final class PdfSourceExtraction {

    static final String EXTRACTOR_ID = "pdfbox-text-v1";

    /** The extraction result: metadata title (may be empty), text, warnings. */
    static final class Extracted {
        final String title;
        final String text;
        final List<String> warnings;

        Extracted(String title, String text, List<String> warnings) {
            this.title = title;
            this.text = text;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    private PdfSourceExtraction() {
    }

    static Extracted extract(byte[] rawBytes) {
        List<String> warnings = new ArrayList<String>();
        if (rawBytes == null || rawBytes.length == 0) {
            return new Extracted("", "", warnings);
        }
        PDDocument document = null;
        try {
            document = PDDocument.load(rawBytes);
            if (document.isEncrypted()) {
                warnings.add("PDF is encrypted; extraction may be incomplete");
            }
            warnings.add("pages=" + document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document).trim();
            if (text.isEmpty()) {
                warnings.add("no extractable text — image-only or scanned PDF"
                        + " (OCR is not part of this slice)");
            }
            PDDocumentInformation info = document.getDocumentInformation();
            String title = info == null || info.getTitle() == null ? "" : info.getTitle().trim();
            return new Extracted(title, text, warnings);
        } catch (InvalidPasswordException locked) {
            warnings.add("PDF is password-protected; no text extracted");
            return new Extracted("", "", warnings);
        } catch (IOException unparseable) {
            warnings.add("PDF could not be parsed: " + unparseable.getMessage());
            return new Extracted("", "", warnings);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException ignored) {
                    // closing a read-only in-memory document failed — nothing to recover
                }
            }
        }
    }
}
