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
 * The reduction (which quantile) is an EXPLICIT parameter and deliberately provisional — measured
 * first, frozen later. Too little data yields {@link Confidence#WEAK}: the caller must then soften
 * its classification instead of pretending it can separate UNEXPLORED from EXTENSION reliably —
 * uncertainty is information. One GLOBAL floor for now; per-anchor floors only on empirical need.
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
        public final List<Double> anchorMissionRelevances;
        public final List<Double> anchorNeighborSimilarities;
        /** Controls whose parent anchor had no vector — skipped, but never silently. */
        public final int orphanedControls;

        Samples(List<Double> anchorMissionRelevances, List<Double> anchorNeighborSimilarities,
                int orphanedControls) {
            this.anchorMissionRelevances = Collections.unmodifiableList(anchorMissionRelevances);
            this.anchorNeighborSimilarities =
                    Collections.unmodifiableList(anchorNeighborSimilarities);
            this.orphanedControls = orphanedControls;
        }
    }

    public enum Confidence {
        OK,
        /** Too few posts or controls: soften classification, ask rather than assert. */
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
        public final int minimumAnchorSamples;
        public final int minimumNeighborSamples;

        public CalibrationParameters(double missionRelevanceQuantile,
                                     double missionRelevanceMargin,
                                     double neighborSimilarityQuantile,
                                     double neighborSimilarityMargin,
                                     int minimumAnchorSamples, int minimumNeighborSamples) {
            this.missionRelevanceQuantile = missionRelevanceQuantile;
            this.missionRelevanceMargin = missionRelevanceMargin;
            this.neighborSimilarityQuantile = neighborSimilarityQuantile;
            this.neighborSimilarityMargin = neighborSimilarityMargin;
            this.minimumAnchorSamples = Math.max(1, minimumAnchorSamples);
            this.minimumNeighborSamples = Math.max(1, minimumNeighborSamples);
        }
    }

    private ScopeFenceCalibrator() {
    }

    /** Measure both raw distributions. Pairwise anchor cosines are deliberately NEVER computed. */
    public static Samples measure(Map<String, float[]> anchorVectorsById,
                                  List<float[]> missionReferenceVectors,
                                  List<CalibrationProbeVector> controls) {
        List<Double> missionRelevances = new ArrayList<Double>();
        for (float[] anchorVector : anchorVectorsById.values()) {
            double relevance = 0.0d;
            for (float[] reference : missionReferenceVectors) {
                relevance = Math.max(relevance,
                        ScopeFenceEvaluator.cosine(anchorVector, reference));
            }
            missionRelevances.add(relevance);
        }
        List<Double> neighborSimilarities = new ArrayList<Double>();
        int orphaned = 0;
        for (CalibrationProbeVector control : controls) {
            float[] anchorVector = anchorVectorsById.get(control.probe.getParentAnchorId());
            if (anchorVector == null) {
                orphaned++;
                continue;
            }
            neighborSimilarities.add(ScopeFenceEvaluator.cosine(anchorVector, control.vector));
        }
        return new Samples(missionRelevances, neighborSimilarities, orphaned);
    }

    /** Derive the floors from the measured distributions; thin data degrades to WEAK, loudly typed. */
    public static FenceCalibration calibrate(Samples samples, CalibrationParameters parameters) {
        Confidence confidence =
                samples.anchorMissionRelevances.size() >= parameters.minimumAnchorSamples
                        && samples.anchorNeighborSimilarities.size()
                                >= parameters.minimumNeighborSamples
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
