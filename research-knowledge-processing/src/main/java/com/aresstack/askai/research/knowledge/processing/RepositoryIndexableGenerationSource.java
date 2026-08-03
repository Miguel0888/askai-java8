package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.ResearchProjectRepository;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link IndexableGenerationSource}: rebuilds a generation's index documents from the CANONICAL
 * persistence — the active passages of {@link ResearchProjectRepository} joined with their persisted vectors
 * from {@link PassageVectorStore}, with the {@code sourceId} taken from the capture (never parsed out of an id).
 * Returns empty when the vectors are not persisted or the requested generation is not the capture's active one,
 * so the worker only resumes at indexing when the durable data genuinely exists.
 */
public final class RepositoryIndexableGenerationSource implements IndexableGenerationSource {

    private final ResearchProjectRepository repository;
    private final PassageVectorStore vectorStore;
    private final String projectId;

    public RepositoryIndexableGenerationSource(ResearchProjectRepository repository,
                                               PassageVectorStore vectorStore, String projectId) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.projectId = projectId;
    }

    @Override
    public List<PassageIndexDocument> loadPersisted(String captureId, String segmentationPipelineVersion,
                                                    String embeddingFingerprint) {
        Map<String, float[]> vectors =
                vectorStore.load(captureId, segmentationPipelineVersion, embeddingFingerprint);
        List<PassageIndexDocument> docs = new ArrayList<PassageIndexDocument>();
        if (vectors.isEmpty()) {
            return docs; // no persisted vectors → run the full pipeline
        }
        ResearchProject project = repository.load(projectId);
        SourceCapture capture = project.captures().get(captureId);
        if (capture == null) {
            return docs;
        }
        String sourceId = capture.getSourceId();
        for (Passage p : project.passages().values()) {
            if (!captureId.equals(p.getCaptureId())
                    || !embeddingFingerprint.equals(p.getEmbeddingFingerprint())
                    || !segmentationPipelineVersion.equals(p.getSegmentationPipelineVersion())) {
                continue; // only THIS capture's active generation for THIS embedding world
            }
            float[] vector = vectors.get(p.getPassageId());
            if (vector == null) {
                continue;
            }
            docs.add(new PassageIndexDocument(p.getPassageId(), captureId, sourceId, p.getText(),
                    p.getHeadingPath(), segmentationPipelineVersion, embeddingFingerprint, vector));
        }
        return docs;
    }
}
