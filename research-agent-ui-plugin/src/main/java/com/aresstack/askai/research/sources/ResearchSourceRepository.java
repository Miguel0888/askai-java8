package com.aresstack.askai.research.sources;

import java.util.List;

/**
 * Structured access to research sources. Deliberately free of Lucene/Swing types: today an in-memory
 * implementation backs it; a Lucene- or project-file-backed adapter can implement the same port later without
 * touching the UI. Create/delete are intentionally omitted — sources are excluded via {@link SourceStatus},
 * not physically deleted.
 */
public interface ResearchSourceRepository {

    List<ResearchSourceRecord> find(SourceQuery query);

    /** @return the record, or {@code null} if the id is unknown. */
    ResearchSourceRecord get(String sourceId);

    SourceUpdateResult update(String sourceId, long expectedRevision, SourceUpdate update);
}
