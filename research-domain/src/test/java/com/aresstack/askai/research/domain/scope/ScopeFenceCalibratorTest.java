package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.CalibrationParameters;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.CalibrationProbeVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.Confidence;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.FenceCalibration;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.Samples;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The two calibrations are statistically DIFFERENT questions: mission relevance comes from the
 * NEGOTIATED anchors only (IN and OUT are user-approved mission-surrounding examples; PROVISIONAL
 * is still the agent's own hypothesis and must never confirm itself), the known-region floor from
 * anchor→neighbor pairs — and NEVER from pairwise anchor distances, which on our deliberately
 * multi-island fence measure the spacing BETWEEN regions, not the radius WITHIN one. Thin or
 * one-sided data degrades to WEAK, and WEAK has one explicit consequence: no hole hunting.
 */
public class ScopeFenceCalibratorTest {

    // Axes: 0=mission, 1=island A, 2=island B (deliberately FAR from A), 3=off-axis noise.
    private static float[] v(float... components) {
        return components;
    }

    /** Two legitimate islands far apart: their pairwise cosine is LOW — that is fence design, not radius. */
    private static List<AnchorVector> anchors() {
        return Arrays.asList(
                new AnchorVector("anchor-helme", ScopeAnchor.Membership.IN, v(0.4f, 1, 0, 0)),
                new AnchorVector("anchor-exo", ScopeAnchor.Membership.OUT, v(0.4f, 0, 1, 0)));
    }

    private static final List<float[]> MISSION = Arrays.asList(v(1, 0.5f, 0.5f, 0));

    private static CalibrationProbeVector control(String id, String parent, float... vector) {
        return new CalibrationProbeVector(new ScopeCalibrationProbe(id, parent, id), vector);
    }

    /** Explicit spike parameters — quantiles/margins/minimums all visible, nothing implied. */
    private static CalibrationParameters parameters(int minimumNegotiated,
                                                    int minimumNeighbors,
                                                    int minimumDistinctParents) {
        return new CalibrationParameters(0.0d, 0.0d, 0.0d, 0.0d,
                minimumNegotiated, minimumNeighbors, minimumDistinctParents);
    }

    @Test
    public void theFloorComesFromNeighborhoodsNeverFromInterIslandDistance() {
        // Local variations around each island: clearly the same region, not paraphrases.
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-helme", 0.4f, 1, 0, 0.3f),
                control("c3", "anchor-exo", 0.4f, 0.25f, 1, 0),
                control("c4", "anchor-exo", 0.4f, 0, 1, 0.3f));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, controls);
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4, 2));

        // The inter-island cosine here is ~0.14 — a floor anywhere near it would be the trap.
        double interIsland = ScopeFenceEvaluator.cosine(v(0.4f, 1, 0, 0), v(0.4f, 0, 1, 0));
        assertTrue("sanity: the islands really are far apart", interIsland < 0.2d);
        assertTrue("the floor reflects LOCAL neighborhood extent (" + calibration.knownRegionFloor
                + "), far above the inter-island distance (" + interIsland + ")",
                calibration.knownRegionFloor > 0.8d);
        assertEquals(Confidence.OK, calibration.confidence);
        assertTrue(calibration.permitsHoleHunting());
        assertEquals("both negotiated anchors contributed a mission-relevance sample",
                2, calibration.samples.anchorMissionRelevances.size());
        assertEquals(2, calibration.samples.distinctParentAnchorsCovered);
    }

    /**
     * The self-confirmation trap: a PROVISIONAL post is the agent's own hypothesis. If it entered
     * the mission calibration, probes similar to the agent's guess would measure mission-relevant
     * and the agent would read its own suggestion as user confirmation. Its NEIGHBORHOOD stays
     * eligible for the known-region floor — there the question is local extent, not approval.
     */
    @Test
    public void aProvisionalPostNeverCalibratesTheMissionButItsNeighborhoodCounts() {
        List<AnchorVector> anchors = Arrays.asList(
                new AnchorVector("anchor-helme", ScopeAnchor.Membership.IN, v(0.4f, 1, 0, 0)),
                new AnchorVector("anchor-exo", ScopeAnchor.Membership.OUT, v(0.4f, 0, 1, 0)),
                // Hypothetical region pointing at the off-axis the mission barely touches:
                new AnchorVector("anchor-guess", ScopeAnchor.Membership.PROVISIONAL,
                        v(0, 0, 0, 1)));
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-guess", 0.1f, 0, 0, 1));

        Samples samples = ScopeFenceCalibrator.measure(anchors, MISSION, controls);

        assertEquals("only the two NEGOTIATED posts calibrate the mission",
                2, samples.anchorMissionRelevances.size());
        for (double relevance : samples.anchorMissionRelevances) {
            assertTrue("the provisional post's near-zero mission relevance is not in the samples",
                    relevance > 0.5d);
        }
        assertEquals("the provisional post's neighborhood still measures local extent",
                2, samples.anchorNeighborSimilarities.size());
        assertEquals(2, samples.distinctParentAnchorsCovered);
        assertEquals("the provisional post still counts as part of the fence",
                3, samples.eligibleAnchorCount);
    }

    /**
     * Coverage, not count: six controls around ONE island measure one region's radius. Applying
     * that as a global floor to a multi-island fence would be confident nonsense — sample COUNT
     * alone must never produce OK.
     */
    @Test
    public void sixControlsAroundOneIslandAreNoGlobalCalibration() {
        List<CalibrationProbeVector> oneIslandControls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-helme", 0.4f, 1, 0, 0.3f),
                control("c3", "anchor-helme", 0.4f, 1, 0.1f, 0.1f),
                control("c4", "anchor-helme", 0.4f, 1, 0.2f, 0.2f),
                control("c5", "anchor-helme", 0.4f, 1, 0.3f, 0),
                control("c6", "anchor-helme", 0.4f, 1, 0, 0.2f));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, oneIslandControls);
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4, 2));

        assertEquals("plenty of samples, but all from one island — WEAK",
                Confidence.WEAK, calibration.confidence);
        assertEquals(1, samples.distinctParentAnchorsCovered);
        assertFalse("and WEAK means: no hole-hunt advisory, the dialog just continues",
                calibration.permitsHoleHunting());
    }

    @Test
    public void thinDataDegradesToWeakInsteadOfPretending() {
        List<CalibrationProbeVector> oneControl = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0));
        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, oneControl);

        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(2, 4, 2));

        assertEquals("one neighbor sample is no calibration — say so, typed",
                Confidence.WEAK, calibration.confidence);
        assertFalse(calibration.permitsHoleHunting());
    }

    /** Two negotiated posts against a minimum of three: early-phase fences are honestly WEAK. */
    @Test
    public void fewNegotiatedPostsAreWeakHoweverManyControlsArrive() {
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-helme", 0.4f, 1, 0, 0.3f),
                control("c3", "anchor-exo", 0.4f, 0.25f, 1, 0),
                control("c4", "anchor-exo", 0.4f, 0, 1, 0.3f));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, controls);
        FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, parameters(3, 4, 2));

        assertEquals(Confidence.WEAK, calibration.confidence);
        assertFalse(calibration.permitsHoleHunting());
    }

    @Test
    public void controlsWithoutTheirAnchorAreCountedNeverSilentlyDropped() {
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("ghost", "anchor-vanished", 0.4f, 0, 0, 1));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), MISSION, controls);

        assertEquals(1, samples.anchorNeighborSimilarities.size());
        assertEquals("the orphan is visible in the samples", 1, samples.orphanedControls);
    }
}
