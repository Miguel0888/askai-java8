package com.aresstack.askai.research.knowledge;

import java.util.List;

/**
 * Groups consecutive sentences of similar meaning into semantic {@link Passage}s (§8). The decisive comparison
 * is next-sentence vs. the RUNNING passage centroid (not merely the previous sentence), with a break when the
 * similarity drops below the configured threshold; document structure may add hard boundaries, and size limits
 * bound passages. The algorithm itself is an infrastructure/application concern — this port is the seam.
 */
public interface SemanticPassageSegmenter {

    List<Passage> segment(ExtractedContent content, List<EmbeddedSentence> sentences);
}
