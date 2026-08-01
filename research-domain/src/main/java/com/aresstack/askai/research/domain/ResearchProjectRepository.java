package com.aresstack.askai.research.domain;

/**
 * Persistence PORT for the aggregate: the project directory stays the source of truth; Lucene, embedding
 * and cluster indexes are always rebuildable derived views. File adapters live outside the domain and
 * follow in their own slice — the domain defines only what operations need.
 */
public interface ResearchProjectRepository {

    ResearchProject load(String projectId);

    void save(ResearchProject project);
}
