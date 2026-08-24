package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.CalibrationParameters;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.CalibrationProbeVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.Confidence;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.FenceCalibration;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator.Samples;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two calibrations are statistically DIFFERENT questions: mission relevance comes from the
 * anchors themselves (IN and OUT are both known mission-surrounding examples), the known-region
 * floor from anchor→neighbor pairs — and NEVER from pairwise anchor distances, which on our
 * deliberately multi-island fence measure the spacing BETWEEN regions, not the radius WITHIN one.
 * Thin data degrades to WEAK instead of pretending certainty.
 */
public class ScopeFenceCalibratorTest {

    // Axes: 0=mission, 1=island A, 2=island B (deliberately FAR from A), 3=off-axis noise.
    private static float[] v(float... components) {
        return components;
    }

    /** Two legitimate islands far apart: their pairwise cosine is LOW — that is fence design, not radius. */
    private static Map<String, float[]> anchors() {
        Map<String, float[]> byId = new LinkedHashMap<String, float[]>();
        byId.put("anchor-helme", v(0.4f, 1, 0, 0));
        byId.put("anchor-exo", v(0.4f, 0, 1, 0));
        return byId;
    }

    private static CalibrationProbeVector control(String id, String parent, float... vector) {
        return new CalibrationProbeVector(new ScopeCalibrationProbe(id, parent, id), vector);
    }

    @Test
    public void theFloorComesFromNeighborhoodsNeverFromInterIslandDistance() {
        List<float[]> mission = Arrays.asList(v(1, 0.5f, 0.5f, 0));
        // Local variations around each island: clearly the same region, not paraphrases.
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("c2", "anchor-helme", 0.4f, 1, 0, 0.3f),
                control("c3", "anchor-exo", 0.4f, 0.25f, 1, 0),
                control("c4", "anchor-exo", 0.4f, 0, 1, 0.3f));

        Samples samples = ScopeFenceCalibrator.measure(anchors(), mission, controls);
        FenceCalibration calibration = ScopeFenceCalibrator.calibrate(samples,
                new CalibrationParameters(0.0d, 0.0d, 0.0d, 0.0d, 2, 4));

        // The inter-island cosine here is ~0.14 — a floor anywhere near it would be the trap.
        double interIsland = ScopeFenceEvaluator.cosine(v(0.4f, 1, 0, 0), v(0.4f, 0, 1, 0));
        assertTrue("sanity: the islands really are far apart", interIsland < 0.2d);
        assertTrue("the floor reflects LOCAL neighborhood extent (" + calibration.knownRegionFloor
                + "), far above the inter-island distance (" + interIsland + ")",
                calibration.knownRegionFloor > 0.8d);
        assertEquals(Confidence.OK, calibration.confidence);
        assertEquals("both anchors contributed a mission-relevance sample",
                2, calibration.samples.anchorMissionRelevances.size());
    }

    @Test
    public void thinDataDegradesToWeakInsteadOfPretending() {
        List<CalibrationProbeVector> oneControl = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0));
        Samples samples = ScopeFenceCalibrator.measure(
                anchors(), Arrays.asList(v(1, 0.5f, 0.5f, 0)), oneControl);

        FenceCalibration calibration = ScopeFenceCalibrator.calibrate(samples,
                new CalibrationParameters(0.0d, 0.0d, 0.0d, 0.0d, 2, 4));

        assertEquals("one neighbor sample is no calibration — say so, typed",
                Confidence.WEAK, calibration.confidence);
    }

    @Test
    public void controlsWithoutTheirAnchorAreCountedNeverSilentlyDropped() {
        List<CalibrationProbeVector> controls = Arrays.asList(
                control("c1", "anchor-helme", 0.4f, 1, 0.25f, 0),
                control("ghost", "anchor-vanished", 0.4f, 0, 0, 1));

        Samples samples = ScopeFenceCalibrator.measure(
                anchors(), Arrays.asList(v(1, 0.5f, 0.5f, 0)), controls);

        assertEquals(1, samples.anchorNeighborSimilarities.size());
        assertEquals("the orphan is visible in the samples", 1, samples.orphanedControls);
    }
}
