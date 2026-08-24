package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ProbeSweepAnalyzer.ProbeSweepResult;
import com.aresstack.askai.research.domain.scope.ProbeSweepAnalyzer.ProbeVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Z3a sweep on synthetic vectors — every agreed case: an irrelevant far-away probe never wins as
 * a "hole" however novel it is; relevant probes bucket as KNOWN/EXCLUDED/PENDING/BOUNDARY/UNEXPLORED
 * via Z3's OWN cascade (sweep-relative line + explicit calibration floor — never Z2's absolute hint);
 * five wordings of the same unknown topic collapse to ONE diverse candidate; two separate unknown
 * islands both survive; an all-holes sweep still reads as all holes. No coverage percentage exists.
 */
public class ProbeSweepAnalyzerTest {

    // Axes: 0=mission frame, 1=IN region, 2=OUT region, 3=island 1, 4=island 2, 5=fridge world.
    private static float[] v(float... components) {
        return components;
    }

    private static final Thresholds FENCE = new Thresholds(0.5d, 0.1d);
    /** Explicit sweep calibration: relevance floor, boundary margin, relative gap, absolute floor. */
    private static final ProbeSweepAnalyzer.SweepParameters SWEEP =
            new ProbeSweepAnalyzer.SweepParameters(0.3d, 0.1d, 0.2d, 0.3d);

    /** The mission frame deliberately spans the whole plausible space — including unexplored axes. */
    private static final List<float[]> MISSION =
            java.util.Collections.singletonList(v(1, 0.4f, 0.4f, 0.5f, 0.5f, 0));

    private static ScopeFenceEvaluator fence() {
        return new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-sensorik", ScopeAnchor.Membership.IN, v(0.3f, 1, 0, 0, 0, 0)),
                new AnchorVector("out-fitness", ScopeAnchor.Membership.OUT, v(0.3f, 0, 1, 0, 0, 0))));
    }

    private static ProbeVector probe(String id, float... vector) {
        return new ProbeVector(new ScopeProbe(id, id), vector);
    }

    private static ProbeReading readingOf(ProbeSweepResult result, String probeId) {
        for (ProbeReading reading : result.getReadings()) {
            if (reading.getProbe().getProbeId().equals(probeId)) {
                return reading;
            }
        }
        throw new AssertionError("no reading for " + probeId);
    }

    @Test
    public void theTwoAxesBucketEveryAgreedCase() {
        List<ProbeVector> probes = Arrays.asList(
                probe("gaswarnkleidung", 0.3f, 1, 0.1f, 0, 0, 0),      // relevant + near IN
                probe("schrittzaehler", 0.3f, 0.1f, 1, 0, 0, 0),       // relevant + near OUT
                probe("grenzfall", 0.3f, 0.7f, 0.7f, 0, 0, 0),         // relevant + both sides
                probe("exoskelette", 0.4f, 0, 0, 1, 0, 0),             // relevant + far from posts
                probe("kuehlschrank", 0, 0, 0, 0, 0, 1));              // novel but OFF-topic

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fence(), FENCE, SWEEP);

        assertEquals(ProbeReading.Category.KNOWN,
                readingOf(result, "gaswarnkleidung").getCategory());
        assertEquals(ProbeReading.Category.EXCLUDED,
                readingOf(result, "schrittzaehler").getCategory());
        assertEquals(ProbeReading.Category.BOUNDARY,
                readingOf(result, "grenzfall").getCategory());
        assertEquals(ProbeReading.Category.UNEXPLORED,
                readingOf(result, "exoskelette").getCategory());
        assertEquals("off-topic stays off-topic however novel it is",
                ProbeReading.Category.IRRELEVANT,
                readingOf(result, "kuehlschrank").getCategory());
        assertTrue("the fridge IS the most novel — novelty alone must never make it interesting",
                readingOf(result, "kuehlschrank").getNovelty()
                        > readingOf(result, "exoskelette").getNovelty());
        assertEquals("the question-worthy pool is boundary + unexplored only",
                2, result.interesting().size());
        assertEquals(1, result.countOf(ProbeReading.Category.KNOWN));
        assertEquals(1, result.countOf(ProbeReading.Category.IRRELEVANT));
    }

    @Test
    public void fiveWordingsOfTheSameHoleAreOneCandidate_twoIslandsStayTwo() {
        List<ProbeVector> probes = new ArrayList<ProbeVector>();
        // Five near-identical wordings of the exoskeleton hole (island 1)...
        for (int variant = 0; variant < 5; variant++) {
            probes.add(probe("exo-" + variant, 0.4f, 0, 0, 1, 0.02f * variant, 0));
        }
        // ...and ONE genuinely different unknown island (island 2).
        probes.add(probe("alleinarbeiter", 0.4f, 0, 0, 0, 1, 0));

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fence(), FENCE, SWEEP);
        assertEquals("all six are unexplored before diversity",
                6, result.countOf(ProbeReading.Category.UNEXPLORED));

        List<ProbeReading> selected = DiverseProbeSelector.select(
                result.interesting(), ProbeSweepAnalyzer.vectorsById(probes),
                new DiverseProbeSelector.Parameters(5, 1.0d, 0.8d));

        assertEquals("one hole per REGION, not per wording", 2, selected.size());
        boolean island1 = false;
        boolean island2 = false;
        for (ProbeReading candidate : selected) {
            if (candidate.getProbe().getProbeId().startsWith("exo-")) {
                island1 = true;
            }
            if (candidate.getProbe().getProbeId().equals("alleinarbeiter")) {
                island2 = true;
            }
        }
        assertTrue("both separate islands survive", island1 && island2);
    }

    /**
     * The resolved contradiction: a probe ON a provisional post is by definition NOT unexplored —
     * the region was raised, the user just has not decided it. Z2's local hint may still say NOVEL
     * (it ignores provisional posts geometrically); Z3's category must say PENDING.
     */
    @Test
    public void aProbeOnAProvisionalPostIsPendingNeverUnexplored() {
        ScopeFenceEvaluator fenceWithProvisional = new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-sensorik", ScopeAnchor.Membership.IN, v(0.3f, 1, 0, 0, 0, 0)),
                new AnchorVector("prov-exo", ScopeAnchor.Membership.PROVISIONAL,
                        v(0.3f, 0, 0, 1, 0, 0))));
        List<ProbeVector> probes = Arrays.asList(
                probe("exoskelette", 0.4f, 0, 0, 1, 0, 0),        // right on the provisional post
                probe("alleinarbeiter", 0.4f, 0, 0, 0, 1, 0));    // genuinely unexplored island

        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fenceWithProvisional, FENCE, SWEEP);

        ProbeReading onProvisional = readingOf(result, "exoskelette");
        assertEquals("raised-but-undecided is PENDING, never a hole",
                ProbeReading.Category.PENDING, onProvisional.getCategory());
        assertTrue("its fence explanation is in fact excellent",
                onProvisional.getKnownSimilarity() > 0.9d);
        assertEquals("the Z2 hint stays diagnostic — and may well disagree",
                ScopeFenceEvaluator.Hint.NOVEL, onProvisional.getLocalFenceHint());
        assertEquals("the truly unraised island stays UNEXPLORED",
                ProbeReading.Category.UNEXPLORED,
                readingOf(result, "alleinarbeiter").getCategory());
        assertTrue("PENDING is question-worthy material",
                result.interesting().contains(onProvisional));
    }

    @Test
    public void anIrrelevantProbeNeverEntersTheSelection() {
        List<ProbeVector> probes = Arrays.asList(
                probe("exoskelette", 0.4f, 0, 0, 1, 0, 0),
                probe("kuehlschrank", 0, 0, 0, 0, 0, 1));
        ProbeSweepResult result = ProbeSweepAnalyzer.analyze(
                probes, MISSION, fence(), FENCE, SWEEP);

        List<ProbeReading> selected = DiverseProbeSelector.select(
                result.interesting(), ProbeSweepAnalyzer.vectorsById(probes),
                new DiverseProbeSelector.Parameters(5, 1.0d, 0.8d));

        assertEquals(1, selected.size());
        assertEquals("exoskelette", selected.get(0).getProbe().getProbeId());
    }
}
