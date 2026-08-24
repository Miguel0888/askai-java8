package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Z3b's calibration: derives the sweep's floors from what the user ALREADY negotiated, instead of
 * hard-coding cosines. Two statistically DIFFERENT questions, deliberately fed from different data:
 * <pre>
 *   minimumMissionRelevance ← mission relevance OF THE ANCHORS themselves.
 *       IN and OUT posts are both known positives for "belongs to the mission's semantic
 *       surroundings" — OUT means "close enough that we explicitly decided against it",
 *       never "off-topic".
 *
 *   knownRegionFloor ← cos(anchor, itsKnownNeighbor) over the ANCHOR_NEIGHBOR controls.
 *       NEVER from pairwise anchor cosines: the fence is deliberately non-convex, its
 *       legitimate islands may lie far apart — inter-anchor distance measures the spacing
 *       BETWEEN regions, not the radius WITHIN which a post still explains a probe.
 * </pre>
 * The reduction (which quantile/margin) is an EXPLICIT parameter and deliberately provisional —
 * the current live values (q=0 i.e. minimum, plus slack) are a MEASUREMENT SPIKE, not a frozen
 * robust formula; whether a low quantile, median−MAD or something else is stabler gets decided on
 * productive measurements, and only then do the values become settings. Epistemics: the controls
 * are MODEL-GENERATED assumptions about each post's neighborhood, so every derived floor rests on
 * "user-negotiated post + synthetic local examples" — never on user-confirmed ground truth. Too
 * little data yields {@link Confidence#WEAK}, and WEAK has ONE explicit consequence
 * ({@link FenceCalibration#permitsHoleHunting()}): no hole-hunt advisory, the normal scoping
 * dialog continues. One GLOBAL floor for now; per-anchor floors only on empirical need.
 */
public final class ScopeFenceCalibrator {

    /** One embedded neighborhood control, ready for measuring. */
    public static final class CalibrationProbeVector {
        public final ScopeCalibrationProbe probe;
        final float[] vector;

        public CalibrationProbeVector(ScopeCalibrationProbe probe, float[] vector) {
            if (probe == null) {
                throw new IllegalArgumentException("probe must not be null");
            }
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("vector must not be empty");
            }
            this.probe = probe;
            this.vector = vector.clone();
        }
    }

    /** The RAW measured distributions — kept so every derived floor stays explainable. */
    public static final class Samples {
        /** One entry per NEGOTIATED (IN/OUT) post — provisional posts never calibrate the mission. */
        public final List<Double> anchorMissionRelevances;
        public final List<Double> anchorNeighborSimilarities;
        /** Controls whose parent anchor had no vector — skipped, but never silently. */
        public final int orphanedControls;
        /** How many DIFFERENT posts contributed neighbor samples — six controls around one post
         *  measure ONE island's radius, not a global floor for a multi-island fence. */
        public final int distinctParentAnchorsCovered;
        /** All posts on the fence (including provisional) — the coverage denominator. */
        public final int eligibleAnchorCount;

        Samples(List<Double> anchorMissionRelevances, List<Double> anchorNeighborSimilarities,
                int orphanedControls, int distinctParentAnchorsCovered, int eligibleAnchorCount) {
            this.anchorMissionRelevances = Collections.unmodifiableList(anchorMissionRelevances);
            this.anchorNeighborSimilarities =
                    Collections.unmodifiableList(anchorNeighborSimilarities);
            this.orphanedControls = orphanedControls;
            this.distinctParentAnchorsCovered = distinctParentAnchorsCovered;
            this.eligibleAnchorCount = eligibleAnchorCount;
        }
    }

    /**
     * Sample SUFFICIENCY of the synthetic neighborhoods — never empirical user confirmation: the
     * controls are model-generated assumptions about each post's local region, so OK means "enough
     * synthetic local examples across enough different posts for a usable heuristic calibration",
     * NOT "this boundary is user-approved ground truth".
     */
    public enum Confidence {
        OK,
        /** Too few negotiated posts, controls, or covered posts: no hole-hunt verdicts from this. */
        WEAK
    }

    /** The derived floors + their confidence + the raw samples they came from. */
    public static final class FenceCalibration {
        public final double minimumMissionRelevance;
        public final double knownRegionFloor;
        public final Confidence confidence;
        public final Samples samples;

        FenceCalibration(double minimumMissionRelevance, double knownRegionFloor,
                         Confidence confidence, Samples samples) {
            this.minimumMissionRelevance = minimumMissionRelevance;
            this.knownRegionFloor = knownRegionFloor;
            this.confidence = confidence;
            this.samples = samples;
        }

        /**
         * The MVP rule for WEAK, made explicit instead of "the caller softens somehow": a weak
         * calibration issues NO hole-hunt verdicts (no UNEXPLORED/EXTENSION advisory) — the normal
         * scoping dialog simply continues. Early in phase 1 the fence naturally has few posts; the
         * sweep matters when the scope FEELS staked out, and by then enough posts exist.
         */
        public boolean permitsHoleHunting() {
            return confidence == Confidence.OK;
        }
    }

    /**
     * The calibration knobs — explicit, provisional, to be frozen only after measuring. The
     * quantiles pick the LOW end of each distribution (a floor should admit the weakest negotiated
     * example, not demand the strongest); the margins are the slack SUBTRACTED below that quantile.
     * The mission margin exists because of a measured live asymmetry: anchors are negotiated full
     * sentences, probes short raw concepts — a legitimate probe routinely scores BELOW the weakest
     * anchor's mission relevance (live: weakest anchor 0.626, legitimate probes down to 0.579,
     * off-topic at 0.482). A floor at the bare anchor quantile would demand anchor-level phrasing
     * from every probe.
     */
    public static final class CalibrationParameters {
        public final double missionRelevanceQuantile;
        public final double missionRelevanceMargin;
        public final double neighborSimilarityQuantile;
        public final double neighborSimilarityMargin;
        /** Minimum NEGOTIATED (IN/OUT) posts — one or two posts are no mission calibration. */
        public final int minimumNegotiatedAnchors;
        public final int minimumNeighborSamples;
        /** Minimum DIFFERENT posts with neighbor controls — one island never calibrates them all. */
        public final int minimumDistinctParentAnchors;

        public CalibrationParameters(double missionRelevanceQuantile,
                                     double missionRelevanceMargin,
                                     double neighborSimilarityQuantile,
                                     double neighborSimilarityMargin,
                                     int minimumNegotiatedAnchors, int minimumNeighborSamples,
                                     int minimumDistinctParentAnchors) {
            this.missionRelevanceQuantile = missionRelevanceQuantile;
            this.missionRelevanceMargin = missionRelevanceMargin;
            this.neighborSimilarityQuantile = neighborSimilarityQuantile;
            this.neighborSimilarityMargin = neighborSimilarityMargin;
            this.minimumNegotiatedAnchors = Math.max(1, minimumNegotiatedAnchors);
            this.minimumNeighborSamples = Math.max(1, minimumNeighborSamples);
            this.minimumDistinctParentAnchors = Math.max(1, minimumDistinctParentAnchors);
        }
    }

    private ScopeFenceCalibrator() {
    }

    /**
     * Measure both raw distributions. Pairwise anchor cosines are deliberately NEVER computed —
     * and neither do PROVISIONAL posts calibrate the mission: only IN and OUT are USER-negotiated
     * "belongs to the mission's surroundings" examples. A provisional post is still the agent's
     * own hypothesis; letting it calibrate relevance would make similar new probes look
     * mission-relevant and the agent would read its own guess as confirmation. For the
     * known-region floor a provisional post's neighborhood IS eligible — there the question is
     * the LOCAL semantic extent of the post, not its negotiated approval.
     */
    public static Samples measure(List<ScopeFenceEvaluator.AnchorVector> anchors,
                                  List<float[]> missionReferenceVectors,
                                  List<CalibrationProbeVector> controls) {
        List<Double> missionRelevances = new ArrayList<Double>();
        Map<String, float[]> vectorsById = new java.util.LinkedHashMap<String, float[]>();
        for (ScopeFenceEvaluator.AnchorVector anchor : anchors) {
            vectorsById.put(anchor.anchorId, anchor.vector);
            if (anchor.membership == ScopeAnchor.Membership.PROVISIONAL) {
                continue;
            }
            double relevance = 0.0d;
            for (float[] reference : missionReferenceVectors) {
                relevance = Math.max(relevance,
                        ScopeFenceEvaluator.cosine(anchor.vector, reference));
            }
            missionRelevances.add(relevance);
        }
        List<Double> neighborSimilarities = new ArrayList<Double>();
        java.util.Set<String> coveredParents = new java.util.LinkedHashSet<String>();
        int orphaned = 0;
        for (CalibrationProbeVector control : controls) {
            float[] anchorVector = vectorsById.get(control.probe.getParentAnchorId());
            if (anchorVector == null) {
                orphaned++;
                continue;
            }
            coveredParents.add(control.probe.getParentAnchorId());
            neighborSimilarities.add(ScopeFenceEvaluator.cosine(anchorVector, control.vector));
        }
        return new Samples(missionRelevances, neighborSimilarities, orphaned,
                coveredParents.size(), anchors.size());
    }

    /** Derive the floors from the measured distributions; thin data degrades to WEAK, loudly typed. */
    public static FenceCalibration calibrate(Samples samples, CalibrationParameters parameters) {
        Confidence confidence =
                samples.anchorMissionRelevances.size() >= parameters.minimumNegotiatedAnchors
                        && samples.anchorNeighborSimilarities.size()
                                >= parameters.minimumNeighborSamples
                        && samples.distinctParentAnchorsCovered
                                >= parameters.minimumDistinctParentAnchors
                        ? Confidence.OK : Confidence.WEAK;
        double missionFloor = quantile(samples.anchorMissionRelevances,
                parameters.missionRelevanceQuantile) - parameters.missionRelevanceMargin;
        double knownFloor = quantile(samples.anchorNeighborSimilarities,
                parameters.neighborSimilarityQuantile) - parameters.neighborSimilarityMargin;
        return new FenceCalibration(missionFloor, knownFloor, confidence, samples);
    }

    /** The q-quantile of the values (0 = min, 1 = max); an empty list yields 0 — nothing to demand. */
    static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        double clamped = Math.max(0.0d, Math.min(1.0d, q));
        int index = (int) Math.floor(clamped * (sorted.size() - 1));
        return sorted.get(index);
    }
}
