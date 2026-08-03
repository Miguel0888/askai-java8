package com.aresstack.askai.research.knowledge;

import java.util.Collection;
import java.util.List;

/**
 * The neutral port over Lucene text search AND the vector store (§11). No Lucene type appears in the domain,
 * application services or Swing. The first productive backend may be Lucene for text plus a persisted simple
 * vector store with brute-force cosine similarity; ANN infrastructure is out of scope. The index is a DERIVED
 * view: {@link #rebuild} recreates it from the persisted passages (§25).
 */
public interface SemanticKnowledgeIndex {

    void indexPassages(Collection<PassageDocument> passages);

    List<PassageHit> keywordSearch(KnowledgeQuery query);

    List<PassageHit> semanticSearch(SemanticQuery query);

    List<PassageHit> findNeighbours(PassageVector vector, int maximumResults);

    void rebuild();

    /** A passage prepared for indexing: its identity, text and (optional) vector. */
    final class PassageDocument {
        private final String passageId;
        private final String sourceId;
        private final String captureId;
        private final String text;
        private final PassageVector vector;

        public PassageDocument(String passageId, String sourceId, String captureId, String text,
                               PassageVector vector) {
            this.passageId = passageId;
            this.sourceId = sourceId;
            this.captureId = captureId;
            this.text = text == null ? "" : text;
            this.vector = vector;
        }

        public String getPassageId() { return passageId; }
        public String getSourceId() { return sourceId; }
        public String getCaptureId() { return captureId; }
        public String getText() { return text; }
        public PassageVector getVector() { return vector; }
    }

    /** A search hit: the passage id and a backend score (higher = more relevant). */
    final class PassageHit {
        private final String passageId;
        private final double score;

        public PassageHit(String passageId, double score) {
            this.passageId = passageId;
            this.score = score;
        }

        public String getPassageId() { return passageId; }
        public double getScore() { return score; }
    }

    /** A keyword query over passage text. */
    final class KnowledgeQuery {
        private final String text;
        private final int maximumResults;

        public KnowledgeQuery(String text, int maximumResults) {
            this.text = text == null ? "" : text;
            this.maximumResults = maximumResults;
        }

        public String getText() { return text; }
        public int getMaximumResults() { return maximumResults; }
    }

    /** A semantic query by vector (must be in a comparable embedding space, §7). */
    final class SemanticQuery {
        private final PassageVector vector;
        private final int maximumResults;

        public SemanticQuery(PassageVector vector, int maximumResults) {
            this.vector = vector;
            this.maximumResults = maximumResults;
        }

        public PassageVector getVector() { return vector; }
        public int getMaximumResults() { return maximumResults; }
    }
}
