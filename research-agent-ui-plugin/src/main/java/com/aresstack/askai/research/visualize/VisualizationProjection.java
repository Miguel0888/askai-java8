package com.aresstack.askai.research.visualize;

/**
 * A DERIVED, rebuildable projection of an artifact's visualization — not a source-of-truth artifact and never
 * revisioned/approved. It records WHICH artifact and WHICH content hash it was built from, so a stale result
 * (the artifact changed while the visualizer ran) is detectable, and it can always be rebuilt from the artifact.
 */
public final class VisualizationProjection {

    private final String sourceArtifactId;
    private final String sourceContentHash;
    private final String phaseId;
    private final VisualizationResult result;

    public VisualizationProjection(String sourceArtifactId, String sourceContentHash, String phaseId,
                                   VisualizationResult result) {
        this.sourceArtifactId = sourceArtifactId == null ? "" : sourceArtifactId;
        this.sourceContentHash = sourceContentHash == null ? "" : sourceContentHash;
        this.phaseId = phaseId == null ? "" : phaseId;
        this.result = result == null ? VisualizationResult.none("") : result;
    }

    public String getSourceArtifactId() {
        return sourceArtifactId;
    }

    public String getSourceContentHash() {
        return sourceContentHash;
    }

    public String getPhaseId() {
        return phaseId;
    }

    public VisualizationResult getResult() {
        return result;
    }
}
