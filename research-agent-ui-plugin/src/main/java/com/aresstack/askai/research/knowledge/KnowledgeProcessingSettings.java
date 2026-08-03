package com.aresstack.askai.research.knowledge;

/**
 * All knowledge-pipeline knobs in one place (§30) — NO magic numbers in the algorithms. Immutable; the single
 * default origin is {@link #defaults()}. Grouped by stage: semantic passage segmentation, topic projection,
 * projection debounce and topic labeling.
 */
public final class KnowledgeProcessingSettings {

    // --- Semantic passage segmentation (§8.5)
    /** Break the current passage when next-sentence-vs-centroid similarity drops below this. */
    public final double semanticBreakThreshold;
    public final int minimumSentencesPerPassage;
    public final int maximumSentencesPerPassage;
    public final int maximumCharactersPerPassage;

    // --- Topic projection (§14)
    /** A passage joins the best existing cluster when its similarity is at least this. */
    public final double topicAssignmentThreshold;
    /** Two clusters become a merge candidate when their centroid similarity is at least this. */
    public final double topicMergeThreshold;
    public final int minimumPassagesPerTopic;
    public final int minimumDistinctSourcesPerTopic;

    // --- Projection debounce/coalescing (§22)
    public final long projectionDebounceMillis;

    // --- Topic labeling (§16, §17)
    public final int topicLabelRepresentativePassageCount;

    public KnowledgeProcessingSettings(double semanticBreakThreshold, int minimumSentencesPerPassage,
                                       int maximumSentencesPerPassage, int maximumCharactersPerPassage,
                                       double topicAssignmentThreshold, double topicMergeThreshold,
                                       int minimumPassagesPerTopic, int minimumDistinctSourcesPerTopic,
                                       long projectionDebounceMillis,
                                       int topicLabelRepresentativePassageCount) {
        this.semanticBreakThreshold = semanticBreakThreshold;
        this.minimumSentencesPerPassage = minimumSentencesPerPassage;
        this.maximumSentencesPerPassage = maximumSentencesPerPassage;
        this.maximumCharactersPerPassage = maximumCharactersPerPassage;
        this.topicAssignmentThreshold = topicAssignmentThreshold;
        this.topicMergeThreshold = topicMergeThreshold;
        this.minimumPassagesPerTopic = minimumPassagesPerTopic;
        this.minimumDistinctSourcesPerTopic = minimumDistinctSourcesPerTopic;
        this.projectionDebounceMillis = projectionDebounceMillis;
        this.topicLabelRepresentativePassageCount = topicLabelRepresentativePassageCount;
    }

    /** THE single default origin (§30). */
    public static KnowledgeProcessingSettings defaults() {
        return new KnowledgeProcessingSettings(
                0.60,   // semanticBreakThreshold
                2,      // minimumSentencesPerPassage
                12,     // maximumSentencesPerPassage
                1_200,  // maximumCharactersPerPassage
                0.72,   // topicAssignmentThreshold
                0.86,   // topicMergeThreshold
                2,      // minimumPassagesPerTopic
                1,      // minimumDistinctSourcesPerTopic
                750L,   // projectionDebounceMillis
                6);     // topicLabelRepresentativePassageCount
    }
}
