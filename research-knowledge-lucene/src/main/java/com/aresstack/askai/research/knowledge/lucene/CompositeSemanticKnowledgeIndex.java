package com.aresstack.askai.research.knowledge.lucene;

import com.aresstack.askai.research.knowledge.processing.index.FileVectorPassageIndex;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * The productive {@link SemanticKnowledgeIndex}: a Lucene text/metadata index ({@link LuceneTextPassageIndex})
 * for keyword search plus a persistent brute-force cosine {@link FileVectorPassageIndex} for semantic search,
 * both rebuildable projections of the canonical knowledge store and both namespaced by embedding fingerprint.
 * Instances are per project (constructed with the project directory); the {@code projectId} argument is a guard.
 * Lucene stays entirely behind this module — the returned {@link SemanticKnowledgeIndex} is Lucene-free.
 */
public final class CompositeSemanticKnowledgeIndex implements SemanticKnowledgeIndex {

    private final String projectId;
    private final LuceneTextPassageIndex text;
    private final FileVectorPassageIndex vectors;

    public CompositeSemanticKnowledgeIndex(File projectDirectory, String projectId) {
        this.projectId = projectId == null ? "" : projectId;
        this.text = new LuceneTextPassageIndex(projectDirectory);
        this.vectors = new FileVectorPassageIndex(projectDirectory);
    }

    @Override
    public void indexPassages(String projectId, Collection<PassageIndexDocument> passages) {
        requireProject(projectId);
        vectors.upsert(passages);
        text.upsert(passages);
    }

    @Override
    public void replacePassagesForCapture(String projectId, String embeddingFingerprint, String captureId,
                                          Collection<PassageIndexDocument> passages) {
        requireProject(projectId);
        vectors.replaceForCapture(embeddingFingerprint, captureId, passages);
        text.replaceForCapture(embeddingFingerprint, captureId, passages);
    }

    @Override
    public List<PassageSearchHit> keywordSearch(String projectId, PassageTextQuery query) {
        requireProject(projectId);
        return text.search(query.getEmbeddingFingerprint(), query.getText(), query.getMaxResults());
    }

    @Override
    public List<PassageSearchHit> semanticSearch(String projectId, PassageSemanticQuery query) {
        requireProject(projectId);
        return vectors.search(query.getEmbeddingFingerprint(), query.getQueryVector(), query.getMaxResults());
    }

    @Override
    public void rebuild(String projectId, Collection<PassageIndexDocument> passages) {
        requireProject(projectId);
        vectors.rebuild(passages);
        text.rebuild(passages);
    }

    @Override
    public void removeProject(String projectId) {
        requireProject(projectId);
        vectors.removeAll();
        text.removeAll();
    }

    private void requireProject(String requested) {
        if (requested != null && !requested.equals(projectId)) {
            throw new IllegalArgumentException("this index serves project '" + projectId
                    + "', not '" + requested + "'");
        }
    }
}
