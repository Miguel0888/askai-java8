package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceUpdate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #39 slice 2 — the NEUTRAL user-import port in front of the ONE commit boundary: every
 * user-provided source (raw HTML, plain text/markdown; file formats join later behind the
 * same port) becomes a regular capture and then travels through the SAME
 * {@link SourceAcceptanceService} the web path uses — dedup, revisions, parked-enrichment
 * and the knowledge-scheduler hook all apply identically; the Swing UI never writes a
 * {@code ResearchSourceRecord} directly.
 *
 * <p>#39 correction — provenance is part of the EVIDENCE CHAIN, never a courtesy: the RAW
 * delivery persists untouched in the {@link ImportedSourceStore} together with the structured
 * extraction record BEFORE the acceptance commit ({@code raw sha256} is the hash of what the
 * user actually delivered; the normalized text is derived and separately hashed). A failed
 * snapshot write FAILS the import — an import never reports success after silently losing
 * its provenance. The source's comment only mirrors a short display note.</p>
 */
public final class SourceImportService {

    /** The supported user-input kinds; FILE/PDF joins behind the same port (issue slice 3). */
    public enum Kind { HTML, TEXT }

    public enum Status { IMPORTED, DUPLICATE, EMPTY, FAILED }

    /** One neutral user input: content plus optional title/origin the user provided. */
    public static final class SourceInput {
        public final Kind kind;
        public final String providedTitle;
        /** Optional origin (URL / base URI for HTML, a path label for files); may be empty. */
        public final String originUri;
        public final String rawContent;

        public SourceInput(Kind kind, String providedTitle, String originUri,
                           String rawContent) {
            this.kind = kind;
            this.providedTitle = providedTitle == null ? "" : providedTitle.trim();
            this.originUri = originUri == null ? "" : originUri.trim();
            this.rawContent = rawContent == null ? "" : rawContent;
        }
    }

    /** The typed outcome; {@code warnings} surface extraction oddities, never block. */
    public static final class ImportOutcome {
        public final Status status;
        public final String sourceId;
        public final String title;
        public final List<String> warnings;

        ImportOutcome(Status status, String sourceId, String title, List<String> warnings) {
            this.status = status;
            this.sourceId = sourceId;
            this.title = title;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    private final CaptureStore captures;
    private final SourceAcceptanceService acceptance;
    private final ResearchSourceRepository repository;
    private final ImportedSourceStore importStore;

    public SourceImportService(CaptureStore captures, SourceAcceptanceService acceptance,
                               ResearchSourceRepository repository,
                               ImportedSourceStore importStore) {
        this.captures = captures;
        this.acceptance = acceptance;
        this.repository = repository;
        this.importStore = importStore;
    }

    /**
     * Import ONE user input through the canonical pipeline. {@code languageCode} is the
     * session's language snapshot for the knowledge job ("" = session default downstream).
     */
    public synchronized ImportOutcome importSource(SourceInput input, String languageCode) {
        List<String> warnings = new ArrayList<String>();
        String text;
        String title;
        String extractor;
        if (input.kind == Kind.HTML) {
            HtmlSourceExtraction.Extracted extracted =
                    HtmlSourceExtraction.extract(input.rawContent, input.originUri);
            text = extracted.text;
            title = !input.providedTitle.isEmpty() ? input.providedTitle
                    : !extracted.title.isEmpty() ? extracted.title : "Imported HTML";
            warnings.addAll(extracted.warnings);
            extractor = HtmlSourceExtraction.EXTRACTOR_ID;
        } else {
            text = input.rawContent.trim();
            title = !input.providedTitle.isEmpty() ? input.providedTitle
                    : firstLineOf(text, "Imported text");
            extractor = "text-passthrough-v1";
        }
        if (text.trim().isEmpty()) {
            return new ImportOutcome(Status.EMPTY, null, title, warnings);
        }
        // The hash of record is the RAW delivery, not the derived text.
        String rawSha256 = CaptureStore.sha256(input.rawContent);
        String snapshotId = ImportedSourceStore.snapshotIdFor(rawSha256);
        try {
            importStore.save(
                    new ImportedSourceStore.ImportedSourceSnapshot(snapshotId,
                            input.kind.name(), input.rawContent, rawSha256, input.originUri,
                            System.currentTimeMillis()),
                    new ImportedSourceStore.ExtractionRecord(snapshotId, extractor,
                            CaptureStore.sha256(text), warnings));
        } catch (IOException lost) {
            // No raw snapshot, no import — a success without provenance would be a lie.
            warnings.add("raw snapshot could not be persisted: " + lost.getMessage());
            return new ImportOutcome(Status.FAILED, null, title, warnings);
        }
        String url = !input.originUri.isEmpty() ? input.originUri
                : "user-import://" + rawSha256.substring(0, 16);
        VisitedCapture capture = captures.record(url, title, text);
        SourceAcceptanceService.Result result = acceptance.accept(capture.getCaptureId(),
                "", false, languageCode == null ? "" : languageCode, "user-import");
        if (result.status == SourceAcceptanceService.Status.UNKNOWN_CAPTURE) {
            return new ImportOutcome(Status.FAILED, null, title, warnings);
        }
        boolean duplicate = result.duplicate
                || result.status == SourceAcceptanceService.Status.ALREADY_ACCEPTED;
        if (!duplicate) {
            try {
                importStore.bindSource(snapshotId, result.sourceId);
            } catch (IOException unbound) {
                // The snapshot itself is safe; the missing link is reported, never silent.
                warnings.add("provenance link could not be persisted: " + unbound.getMessage());
            }
            writeDisplayNote(result.sourceId, input, extractor, rawSha256, snapshotId, warnings);
        }
        return new ImportOutcome(duplicate ? Status.DUPLICATE : Status.IMPORTED,
                result.sourceId, result.title, warnings);
    }

    /**
     * A SHORT display note in the comment — pure convenience mirroring the
     * {@link ImportedSourceStore} truth; best effort AFTER the commit, never authoritative.
     */
    private void writeDisplayNote(String sourceId, SourceInput input, String extractor,
                                  String rawSha256, String snapshotId, List<String> warnings) {
        try {
            ResearchSourceRecord record = repository.get(sourceId);
            if (record == null) {
                return;
            }
            StringBuilder note = new StringBuilder("User import (")
                    .append(input.kind.name().toLowerCase(java.util.Locale.ROOT))
                    .append("), extractor=").append(extractor)
                    .append(", sha256=").append(rawSha256.substring(0, 16))
                    .append(", snapshot=").append(snapshotId);
            if (!input.originUri.isEmpty()) {
                note.append(", origin=").append(input.originUri);
            }
            for (String warning : warnings) {
                note.append("\nwarning: ").append(warning);
            }
            String existing = record.getComment();
            String comment = existing == null || existing.trim().isEmpty()
                    ? note.toString() : existing + "\n" + note;
            repository.update(sourceId, record.getRevision(),
                    SourceUpdate.from(record).comment(comment).build());
        } catch (RuntimeException displayOnly) {
            // the durable provenance lives in the ImportedSourceStore; the note is cosmetic
        }
    }

    private static String firstLineOf(String text, String fallback) {
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
            }
        }
        return fallback;
    }
}
