package com.aresstack.askai.research.domain.scope;

/**
 * ONE anchor-neighborhood CONTROL for calibration — typologically separate from {@link ScopeProbe}:
 * it is not a hole-search hypothesis but a known LOCAL variation of a specific post ("a concrete
 * example that clearly still falls under this anchor"), tied to it via {@link #getParentAnchorId()}.
 * The distribution of {@code cos(anchor, itsNeighbor)} is what calibrates the known-region floor —
 * NOT the pairwise anchor distances, which only measure how far apart legitimate ISLANDS lie.
 * Controls exist solely for calibration; they never enter the sweep's hole search and never become
 * anchors.
 * <p>
 * Deliberately NOT mere paraphrases (those score ~.95 and would make the floor far too strict):
 * different concrete examples inside the same region measure its real local extent.
 */
public final class ScopeCalibrationProbe {

    private final String probeId;
    private final String parentAnchorId;
    private final String semanticText;

    public ScopeCalibrationProbe(String probeId, String parentAnchorId, String semanticText) {
        if (probeId == null || probeId.trim().isEmpty()) {
            throw new IllegalArgumentException("probeId must not be empty");
        }
        if (parentAnchorId == null || parentAnchorId.trim().isEmpty()) {
            throw new IllegalArgumentException("parentAnchorId must not be empty");
        }
        if (semanticText == null || semanticText.trim().isEmpty()) {
            throw new IllegalArgumentException("semanticText must not be empty");
        }
        this.probeId = probeId.trim();
        this.parentAnchorId = parentAnchorId.trim();
        this.semanticText = semanticText.trim();
    }

    public String getProbeId() {
        return probeId;
    }

    /** The post whose local neighborhood this control belongs to. */
    public String getParentAnchorId() {
        return parentAnchorId;
    }

    public String getSemanticText() {
        return semanticText;
    }
}
