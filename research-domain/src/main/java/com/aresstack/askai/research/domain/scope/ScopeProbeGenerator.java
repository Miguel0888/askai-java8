package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The EXCHANGEABLE probe source of the sweep — deliberately a port: Z3's biggest open risk is not
 * the cosine math but whether the generator samples the space broadly enough (a hole the generator
 * never names cannot be found by any evaluator). Implementations (prompt v1/v2, small/large model,
 * several independent batches, fixed test lists) compete behind this seam without touching any
 * fence mathematics. A generator must NOT merely paraphrase the known facets.
 */
public interface ScopeProbeGenerator {

    /** What the generator works from — the draft's coarse frame, never the fence geometry. */
    final class ProbeGenerationRequest {
        private final String mission;
        private final List<String> domains;
        private final List<String> contexts;
        /** The labels already on the fence — so the generator can AVOID mere paraphrases. */
        private final List<String> knownFacetLabels;
        /** The posts themselves — the neighborhood controls need their ids and semantic texts. */
        private final List<ScopeAnchor> anchors;
        private final int targetCount;

        public ProbeGenerationRequest(String mission, List<String> domains, List<String> contexts,
                                      List<String> knownFacetLabels, List<ScopeAnchor> anchors,
                                      int targetCount) {
            this.mission = mission == null ? "" : mission.trim();
            this.domains = copy(domains);
            this.contexts = copy(contexts);
            this.knownFacetLabels = copy(knownFacetLabels);
            this.anchors = Collections.unmodifiableList(new ArrayList<ScopeAnchor>(
                    anchors == null ? Collections.<ScopeAnchor>emptyList() : anchors));
            this.targetCount = Math.max(1, targetCount);
        }

        public List<ScopeAnchor> getAnchors() {
            return anchors;
        }

        public String getMission() {
            return mission;
        }

        public List<String> getDomains() {
            return domains;
        }

        public List<String> getContexts() {
            return contexts;
        }

        public List<String> getKnownFacetLabels() {
            return knownFacetLabels;
        }

        public int getTargetCount() {
            return targetCount;
        }

        private static List<String> copy(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<String>(
                    values == null ? Collections.<String>emptyList() : values));
        }
    }

    /**
     * ONE model call, two kinds of output: the BROAD probes hunt holes; the ANCHOR_NEIGHBOR
     * controls (2-3 per post: different concrete examples clearly INSIDE that post's region, never
     * mere paraphrases) exist solely to calibrate the known-region floor. Keeping both in one batch
     * keeps the orientation sweep fast.
     */
    final class ProbeGeneration {
        private final List<ScopeProbe> broadProbes;
        private final List<ScopeCalibrationProbe> calibrationProbes;

        public ProbeGeneration(List<ScopeProbe> broadProbes,
                               List<ScopeCalibrationProbe> calibrationProbes) {
            this.broadProbes = Collections.unmodifiableList(new ArrayList<ScopeProbe>(
                    broadProbes == null ? Collections.<ScopeProbe>emptyList() : broadProbes));
            this.calibrationProbes = Collections.unmodifiableList(
                    new ArrayList<ScopeCalibrationProbe>(calibrationProbes == null
                            ? Collections.<ScopeCalibrationProbe>emptyList() : calibrationProbes));
        }

        public List<ScopeProbe> getBroadProbes() {
            return broadProbes;
        }

        public List<ScopeCalibrationProbe> getCalibrationProbes() {
            return calibrationProbes;
        }
    }

    /** Many DIVERSE concrete concepts plus the per-anchor neighborhood controls — one model call. */
    ProbeGeneration generate(ProbeGenerationRequest request);
}
