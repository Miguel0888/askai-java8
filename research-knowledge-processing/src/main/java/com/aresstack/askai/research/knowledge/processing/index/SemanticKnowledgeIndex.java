package com.aresstack.askai.research.knowledge.processing.index;

import java.util.Collection;
import java.util.List;

/**
 * The neutral port for the searchable projection of a project's knowledge corpus — a Lucene text/metadata index
 * plus a vector index, behind ONE Lucene-free interface (no Lucene/vector-store type ever appears here). It is
 * strictly a PROJECTION: the {@code ResearchProjectRepository} + persisted passage vectors are the source of
 * truth, and {@link #rebuild} can regenerate the whole index from them WITHOUT re-embedding.
 *
 * <p>Every write and query is namespaced by {@code (projectId, embeddingFingerprint)}: a change of embedding
 * model creates a NEW semantic space — old and new passage vectors are never compared. The active session's
 * fingerprint decides which namespace the worker writes to.</p>
 */
public interface SemanticKnowledgeIndex {

    /**
     * Upsert passages into their {@code (projectId, embeddingFingerprint)} namespace (idempotent per
     * {@code passageId} — re-indexing the same passage never produces a duplicate hit).
     */
    void indexPassages(String projectId, Collection<PassageIndexDocument> passages);

    /**
     * Replace ALL of a capture's passages in the given namespace with {@code passages} (an idempotent, atomic
     * per-capture upsert). This is how a NEW active processing generation supersedes the old one: the previous
     * generation's passages leave the active search space instead of lingering forever.
     */
    void replacePassagesForCapture(String projectId, String embeddingFingerprint, String captureId,
                                   Collection<PassageIndexDocument> passages);

    /** Keyword/metadata search within one namespace. */
    List<PassageSearchHit> keywordSearch(String projectId, PassageTextQuery query);

    /** Semantic (cosine) search within one namespace; a cross-world query vector is rejected. */
    List<PassageSearchHit> semanticSearch(String projectId, PassageSemanticQuery query);

    /**
     * Drop the project's whole index and rebuild it from the given active passages (grouped by their own
     * namespace) — recovery from index loss/corruption/schema change, never re-embedding.
     */
    void rebuild(String projectId, Collection<PassageIndexDocument> passages);

    /** Remove a project's entire index (all namespaces). */
    void removeProject(String projectId);
}
