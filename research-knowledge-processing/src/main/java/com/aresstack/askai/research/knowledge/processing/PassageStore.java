package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import java.util.List;

/**
 * Persists the sentences and passages of one processed capture. The productive adapter records them onto the
 * {@code research-domain} {@code ResearchProject} aggregate (via {@code recordSentences}/{@code recordPassages})
 * and saves it through {@code ResearchProjectRepository} — the project directory stays the single source of
 * truth and the embedding/index views are rebuildable. NOTE: no productive file-backed ResearchProjectRepository
 * exists yet (it is a domain port only); the productive adapter is a C3/C4 concern, so C2/consolidation wires
 * this port with test doubles only.
 */
public interface PassageStore {

    void store(SourceCapture capture, List<Sentence> sentences, List<Passage> passages);
}
