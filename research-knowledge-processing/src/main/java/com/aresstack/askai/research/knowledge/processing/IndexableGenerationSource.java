package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;

import java.util.List;

/**
 * Loads the ALREADY-PERSISTED, active passages of one processing generation as index documents (passage
 * metadata + its persisted vector), so the worker can RESUME at the indexing stage after a transient index
 * failure WITHOUT re-running OpenNLP/embedding. The productive adapter reads them from the {@code
 * ResearchProjectRepository} (passages + capture for the sourceId) joined with the persisted passage vectors.
 *
 * <p>Returns an empty list when this generation is not (fully) persisted yet — then the worker runs the full
 * pipeline. Persistence is the source of truth: a returned document means "this passage + its exact vector are
 * durably stored", so re-embedding is never needed on retry.</p>
 */
public interface IndexableGenerationSource {

    List<PassageIndexDocument> loadPersisted(String captureId, String segmentationPipelineVersion,
                                             String embeddingFingerprint, String languageCode);
}
