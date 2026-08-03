package com.aresstack.askai.research.knowledge;

import java.util.List;

/**
 * Persists passages as project facts (§9), independent of any Lucene document id. Embedding vectors may be
 * stored alongside or separately (§9); this port hides that. Historical passages are kept even when a source
 * is later marked irrelevant (§12, §21) — relevance changes the projection, never the stored facts.
 */
public interface PassageRepository {

    /** Persist a passage and its final vector (the vector may be null before embedding). */
    void save(Passage passage, PassageVector vector);

    List<Passage> findByCaptureId(String captureId);

    List<Passage> findBySourceId(String sourceId);

    /** Load a passage's stored vector, or {@code null} when none was persisted. */
    PassageVector loadVector(String passageId);

    /** All persisted passages (used to rebuild derived indices/projections, §25). */
    List<Passage> findAll();
}
