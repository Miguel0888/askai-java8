package com.aresstack.askai.research.knowledge;

/**
 * A {@link DetectedSentence} paired with its embedding vector (§7). The {@link SemanticPassageSegmenter}
 * consumes a stream of these, comparing each next sentence against the running passage centroid.
 */
public final class EmbeddedSentence {

    private final DetectedSentence sentence;
    private final PassageVector vector;

    public EmbeddedSentence(DetectedSentence sentence, PassageVector vector) {
        this.sentence = sentence;
        this.vector = vector;
    }

    public DetectedSentence getSentence() {
        return sentence;
    }

    public PassageVector getVector() {
        return vector;
    }
}
