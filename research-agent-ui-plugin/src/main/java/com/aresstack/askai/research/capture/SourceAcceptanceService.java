package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceRelevance;
import com.aresstack.askai.research.sources.SourceReliability;
import com.aresstack.askai.research.sources.SourceStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ONLY path from a visited capture to a persistent source. Acceptance is atomic (the source record is
 * fully built, then committed, THEN indexed — an index failure never loses the source, it just marks the
 * index STALE) and idempotent (re-accepting the same capture returns ALREADY_ACCEPTED with the existing id;
 * no second source, no second index entry).
 *
 * <p>Dedup rules (canonical URL + content hash, no near-duplicate/embedding search in the MVP): same
 * canonical URL + same hash → identical → ALREADY_ACCEPTED; different URL + same hash → content duplicate →
 * accepted but flagged DUPLICATE; same URL + different hash → a new revision of the page → a fresh source.
 * The result stays compact for the model: status, source id, title, passage count, duplicate flag — never
 * HTML or the full extracted text.</p>
 */
public final class SourceAcceptanceService {

    /** Commits a fully-built record to the source store (in-memory or file-backed). */
    public interface SourceCreator {
        void create(ResearchSourceRecord record);
    }

    public enum Status { ACCEPTED, ALREADY_ACCEPTED, UNKNOWN_CAPTURE }

    public static final class Result {
        public final Status status;
        public final String sourceId;
        public final String title;
        public final int passageCount;
        public final boolean duplicate;
        public final boolean indexStale;

        Result(Status status, String sourceId, String title, int passageCount,
               boolean duplicate, boolean indexStale) {
            this.status = status;
            this.sourceId = sourceId;
            this.title = title;
            this.passageCount = passageCount;
            this.duplicate = duplicate;
            this.indexStale = indexStale;
        }

        /** Compact tool-facing rendering — no HTML, no full text. */
        public String render() {
            return "status=" + status + " source_id=" + (sourceId == null ? "-" : sourceId)
                    + " title=\"" + title + "\" passage_count=" + passageCount
                    + " duplicate=" + duplicate + (indexStale ? " index=STALE" : "");
        }
    }

    private final CaptureStore captures;
    private final com.aresstack.askai.research.sources.ResearchSourceRepository repository;
    private final SourceCreator creator;
    private final ResearchSearchIndex index;
    private final AtomicLong sourceIds = new AtomicLong();
    /** captureId → sourceId of a completed acceptance (idempotency). */
    private final Map<String, String> acceptedByCapture = new HashMap<String, String>();

    public SourceAcceptanceService(CaptureStore captures,
                                   com.aresstack.askai.research.sources.ResearchSourceRepository repository,
                                   SourceCreator creator, ResearchSearchIndex index) {
        this.captures = captures;
        this.repository = repository;
        this.creator = creator;
        this.index = index;
    }

    public synchronized Result accept(String captureId) {
        // Idempotency first: a completed acceptance always returns the same source id.
        String existing = acceptedByCapture.get(captureId);
        if (existing != null) {
            ResearchSourceRecord record = repository.get(existing);
            return new Result(Status.ALREADY_ACCEPTED, existing,
                    record == null ? "" : record.getTitle(), 0,
                    record != null && record.getStatus() == SourceStatus.DUPLICATE, false);
        }
        VisitedCapture capture = captures.get(captureId);
        if (capture == null) {
            return new Result(Status.UNKNOWN_CAPTURE, null, "", 0, false, false);
        }

        // Dedup against the already-accepted sources.
        boolean contentDuplicate = false;
        for (ResearchSourceRecord source : repository.find(SourceQuery.all())) {
            boolean sameUrl = capture.getCanonicalUrl().equals(
                    CaptureStore.canonicalize(source.getUrl()));
            boolean sameHash = capture.getContentHash().equals(source.getChecksum());
            if (sameUrl && sameHash) {
                // Identical page already accepted: idempotent outcome against the existing source.
                acceptedByCapture.put(captureId, source.getSourceId());
                return new Result(Status.ALREADY_ACCEPTED, source.getSourceId(),
                        source.getTitle(), 0, source.getStatus() == SourceStatus.DUPLICATE, false);
            }
            if (sameHash) {
                contentDuplicate = true; // different URL, same content
            }
            // sameUrl && !sameHash → a changed page: falls through to a fresh source (new revision).
        }

        // Extract (already-clean text; the chain only normalizes residues and counts passages).
        DocumentExtractor.ExtractedDocument doc = DocumentExtractor.Chain.extract(
                new DocumentExtractor.DocumentInput("text/plain", "", capture.getText()));
        String title = capture.getTitle().isEmpty() ? doc.getTitle() : capture.getTitle();

        // Build the full record, then commit atomically; the capture's assessment travels along.
        String sourceId = "source-" + sourceIds.incrementAndGet();
        ResearchSourceRecord record = ResearchSourceRecord.builder(sourceId)
                .title(title)
                .origin(hostOf(capture.getCanonicalUrl()))
                .url(capture.getUrl())
                .sourceType(capture.getSourceType() == null ? "web" : capture.getSourceType())
                .capturedAt(capture.getCapturedAt())
                .comment(capture.getAssessmentNote() == null ? "" : capture.getAssessmentNote())
                .relevance(parseRelevance(capture.getRelevance()))
                .reliability(SourceReliability.UNKNOWN)
                .status(contentDuplicate ? SourceStatus.DUPLICATE : SourceStatus.NEW)
                .snapshotReference("")
                .checksum(capture.getContentHash())
                .revision(1L)
                .build();
        creator.create(record);
        acceptedByCapture.put(captureId, sourceId);

        // Index AFTER the commit: a failure marks the index stale but never loses the source.
        boolean stale = false;
        try {
            index.index(new ResearchSearchIndex.ResearchSourceDocument(sourceId, title, doc.getText()));
        } catch (RuntimeException ex) {
            stale = true;
        }
        return new Result(Status.ACCEPTED, sourceId, title, doc.getPassageCount(), contentDuplicate, stale);
    }

    private static SourceRelevance parseRelevance(String v) {
        if (v == null) {
            return SourceRelevance.UNKNOWN;
        }
        try {
            return SourceRelevance.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SourceRelevance.UNKNOWN;
        }
    }

    private static String hostOf(String canonicalUrl) {
        int i = canonicalUrl.indexOf("://");
        if (i < 0) {
            return canonicalUrl;
        }
        String rest = canonicalUrl.substring(i + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
