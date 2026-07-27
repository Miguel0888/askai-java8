package com.aresstack.askai.research.sources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic in-memory {@link ResearchSourceRepository} for the clickdummy, tests and the fake agent.
 * Optimistic locking on {@code revision} prevents lost updates. Seeded with a few sources, including one with a
 * deliberately orphaned section link so the UI can exercise the orphan case. A real project-store/Lucene
 * adapter can replace this behind the port.
 */
public final class InMemoryResearchSourceRepository implements ResearchSourceRepository {

    private final Map<String, ResearchSourceRecord> byId = new LinkedHashMap<String, ResearchSourceRecord>();

    public InMemoryResearchSourceRepository() {
        seed(ResearchSourceRecord.builder("src1")
                .title("PF4J plugin framework").origin("github.com/pf4j/pf4j").url("https://github.com/pf4j/pf4j")
                .sourceType("repository").author("Decebal Suiu").capturedAt(1000L)
                .linkedSectionIds(Arrays.asList("s3")).comment("Core plugin mechanism.")
                .relevance(SourceRelevance.HIGH).reliability(SourceReliability.PRIMARY_SOURCE)
                .status(SourceStatus.ACCEPTED).snapshotReference("snap/src1.html").checksum("aaaa")
                .revision(1L).build());
        seed(ResearchSourceRecord.builder("src2")
                .title("Solon AI").origin("solon.noear.org").url("https://solon.noear.org")
                .sourceType("docs").author("noear").capturedAt(1000L)
                .linkedSectionIds(Arrays.asList("s2", "s2a")).comment("Runtime candidate.")
                .relevance(SourceRelevance.MEDIUM).reliability(SourceReliability.MEDIUM)
                .status(SourceStatus.REVIEWED).snapshotReference("snap/src2.html").checksum("bbbb")
                .revision(1L).build());
        seed(ResearchSourceRecord.builder("src3")
                .title("Legacy note").origin("internal").url("")
                .sourceType("note").author("").capturedAt(1000L)
                .linkedSectionIds(Arrays.asList("s99-removed")) // orphan: section no longer in the outline
                .comment("Kept for history.").relevance(SourceRelevance.LOW)
                .reliability(SourceReliability.UNKNOWN).status(SourceStatus.NEW)
                .snapshotReference("").checksum("cccc").revision(1L).build());
    }

    private void seed(ResearchSourceRecord record) {
        byId.put(record.getSourceId(), record);
    }

    /** Add or overwrite a record verbatim (mirrors the file repository; used by source acceptance). */
    public void put(ResearchSourceRecord record) {
        byId.put(record.getSourceId(), record);
    }

    /** An EMPTY repository (no demo seeds) for fresh acceptance flows and tests. */
    public static InMemoryResearchSourceRepository empty() {
        InMemoryResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        repo.byId.clear();
        return repo;
    }

    @Override
    public List<ResearchSourceRecord> find(SourceQuery query) {
        SourceQuery effective = query == null ? SourceQuery.all() : query;
        List<ResearchSourceRecord> result = new ArrayList<ResearchSourceRecord>();
        for (ResearchSourceRecord record : byId.values()) {
            if (effective.matches(record)) {
                result.add(record);
            }
        }
        return result;
    }

    @Override
    public ResearchSourceRecord get(String sourceId) {
        return sourceId == null ? null : byId.get(sourceId);
    }

    @Override
    public SourceUpdateResult update(String sourceId, long expectedRevision, SourceUpdate update) {
        ResearchSourceRecord current = byId.get(sourceId);
        if (current == null) {
            return SourceUpdateResult.notFound(sourceId);
        }
        if (current.getRevision() != expectedRevision) {
            return SourceUpdateResult.conflict(current);
        }
        ResearchSourceRecord next = current.toBuilder()
                .title(update.getTitle())
                .origin(update.getOrigin())
                .url(update.getUrl())
                .sourceType(update.getSourceType())
                .author(update.getAuthor())
                .linkedSectionIds(update.getLinkedSectionIds())
                .comment(update.getComment())
                .relevance(update.getRelevance())
                .reliability(update.getReliability())
                .status(update.getStatus())
                .revision(current.getRevision() + 1L)
                .build();
        byId.put(sourceId, next);
        return SourceUpdateResult.updated(next);
    }
}
