package com.aresstack.askai.research.visualize;

/**
 * Turns an {@link ArtifactSnapshot} into a derived {@link VisualizationResult}. GENERIC: any artifact can be
 * visualized, not just the research brief. A visualizer has NO workflow authority — it never mutates an
 * artifact, approves, transitions a phase, produces a search query, writes to the chat, or blocks the main
 * agent. It only produces a rebuildable, derived projection; returning {@link VisualizationResult#none} is
 * always allowed. This is the HOST-side consumer of a persisted artifact, not part of any agent prompt turn.
 */
public interface ArtifactVisualizationService {

    VisualizationResult visualize(ArtifactSnapshot snapshot);
}
