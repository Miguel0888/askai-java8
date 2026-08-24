package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGeneration;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.aresstack.askai.research.domain.scope.ScopeSweepService.SweepPlan;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The host-led orchestration: every gate produces its TYPED outcome — a failed or untrusted sweep
 * is never "0 unexplored regions". The revision is pinned at start and checked twice (after the
 * expensive generation, and before publishing); the embedding fingerprint is pinned once and a
 * mismatching embedder is refused before any cosine is computed. Nothing here mutates scope and
 * nothing triggers itself.
 */
public class ScopeSweepServiceTest {

    // Axes: 0=mission, 1=IN island, 2=OUT island, 3=hole island.
    private static float[] v(float... components) {
        return components;
    }

    private static final String REVISION = "rev-7";
    private static final String FINGERPRINT = "nomic@1";

    /** Deterministic synthetic embedder: text→vector lookup, one batch, fixed fingerprint. */
    private static final class MapEmbedder implements ScopeSweepService.SweepEmbedder {
        final Map<String, float[]> byText = new LinkedHashMap<String, float[]>();
        private final String fingerprint;

        MapEmbedder(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        @Override
        public String modelFingerprint() {
            return fingerprint;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> vectors = new ArrayList<float[]>();
            for (String text : texts) {
                float[] vector = byText.get(text);
                if (vector == null) {
                    return null; // unknown text = deterministic embedding failure
                }
                vectors.add(vector);
            }
            return vectors;
        }
    }

    private static final class FixedGenerator implements ScopeProbeGenerator {
        private final ProbeGenerationResult result;
        /** Lets a test move the scope exactly while the "model" is busy. */
        Runnable duringGeneration = null;

        FixedGenerator(ProbeGenerationResult result) {
            this.result = result;
        }

        @Override
        public ProbeGenerationResult generate(ProbeGenerationRequest request) {
            if (duringGeneration != null) {
                duringGeneration.run();
            }
            return result;
        }
    }

    private static final class MutableRevision implements ScopeSweepService.ScopeRevisionProbe {
        volatile String revision = REVISION;

        @Override
        public String currentRevision() {
            return revision;
        }
    }

    private static ProbeGenerationRequest request() {
        return new ProbeGenerationRequest("Mission", null, null, null,
                Arrays.asList(
                        new ScopeAnchor("anchor-in", "f1", "Sensorik", ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-out", "f2", "Fitness", ScopeAnchor.Membership.OUT)),
                2);
    }

    private static ProbeGenerationResult completeGeneration() {
        return ProbeGenerationResult.ok(new ProbeGeneration(
                Arrays.asList(new ScopeProbe("probe-0001", "bekanntes Thema"),
                        new ScopeProbe("probe-0002", "unbekannte Insel")),
                Arrays.asList(
                        new ScopeCalibrationProbe("control-0001", "anchor-in", "Sensorik-Nachbar"),
                        new ScopeCalibrationProbe("control-0002", "anchor-out", "Fitness-Nachbar")),
                2), "");
    }

    /** An embedder that knows every text of {@link #completeGeneration()} — the READY geometry. */
    private static MapEmbedder fullEmbedder() {
        MapEmbedder embedder = new MapEmbedder(FINGERPRINT);
        embedder.byText.put("Mission", v(1, 0.6f, 0.6f, 0.6f));
        embedder.byText.put("bekanntes Thema", v(0.5f, 1, 0, 0));
        embedder.byText.put("unbekannte Insel", v(0.5f, 0, 0, 1));
        embedder.byText.put("Sensorik-Nachbar", v(0.5f, 1, 0.2f, 0));
        embedder.byText.put("Fitness-Nachbar", v(0.5f, 0.2f, 1, 0));
        return embedder;
    }

    private static List<AnchorVector> anchorVectors() {
        return Arrays.asList(
                new AnchorVector("anchor-in", ScopeAnchor.Membership.IN, v(0.5f, 1, 0, 0)),
                new AnchorVector("anchor-out", ScopeAnchor.Membership.OUT, v(0.5f, 0, 1, 0)));
    }

    private static SweepPlan plan() {
        return new SweepPlan(REVISION, FINGERPRINT, request(), anchorVectors(),
                Arrays.asList("Mission"), new Thresholds(0.5d, 0.1d),
                new ScopeFenceCalibrator.CalibrationParameters(0.0d, 0.1d, 0.0d, 0.1d, 2, 2),
                0.1d, 0.0d, new DiverseProbeSelector.Parameters(3, 1.0d, 0.8d));
    }

    @Test
    public void theHappyPathEndsReadyWithCandidatesAndCalibration() {
        ScopeSweepService service = new ScopeSweepService(
                new FixedGenerator(completeGeneration()), fullEmbedder(), new MutableRevision());

        ScopeSweepOutcome outcome = service.run(plan());

        assertEquals(ScopeSweepOutcome.Status.READY, outcome.getStatus());
        assertTrue(outcome.getCalibration().permitsHoleHunting());
        assertEquals("the unknown island is the question-worthy candidate",
                1, outcome.getDiverseCandidates().size());
        assertEquals("unbekannte Insel",
                outcome.getDiverseCandidates().get(0).getProbe().getSemanticText());
        assertEquals("the known probe stayed KNOWN, not a hole",
                1, outcome.getSweep().countOf(ProbeReading.Category.KNOWN));
    }

    @Test
    public void aFailedGenerationIsItsOwnFactNeverZeroHoles() {
        ScopeSweepService service = new ScopeSweepService(
                new FixedGenerator(ProbeGenerationResult.failure(
                        ProbeGenerationResult.Status.TIMEOUT, "read timed out")),
                fullEmbedder(), new MutableRevision());

        ScopeSweepOutcome outcome = service.run(plan());

        assertEquals(ScopeSweepOutcome.Status.GENERATION_FAILED, outcome.getStatus());
        assertEquals(ProbeGenerationResult.Status.TIMEOUT, outcome.getGeneratorStatus());
        assertFalse(outcome.isReady());
        assertTrue(outcome.getDiverseCandidates().isEmpty());
    }

    @Test
    public void aThinBroadSampleGatesTheHoleHuntWithItsCounts() {
        ProbeGenerationResult thin = ProbeGenerationResult.ok(new ProbeGeneration(
                Arrays.asList(new ScopeProbe("probe-0001", "bekanntes Thema")),
                Arrays.asList(
                        new ScopeCalibrationProbe("control-0001", "anchor-in", "Sensorik-Nachbar"),
                        new ScopeCalibrationProbe("control-0002", "anchor-out", "Fitness-Nachbar")),
                2), "");
        ScopeSweepService service = new ScopeSweepService(
                new FixedGenerator(thin), fullEmbedder(), new MutableRevision());

        ScopeSweepOutcome outcome = service.run(plan());

        assertEquals(ScopeSweepOutcome.Status.BROAD_SAMPLE_INCOMPLETE, outcome.getStatus());
        assertEquals(2, outcome.getRequestedBroadCount());
        assertEquals(1, outcome.getAcceptedBroadCount());
    }

    @Test
    public void aWeakCalibrationGatesTheHoleHuntWithItsCoverage() {
        // Both controls parented by the SAME post: coverage incomplete → WEAK.
        ProbeGenerationResult oneIsland = ProbeGenerationResult.ok(new ProbeGeneration(
                Arrays.asList(new ScopeProbe("probe-0001", "bekanntes Thema"),
                        new ScopeProbe("probe-0002", "unbekannte Insel")),
                Arrays.asList(
                        new ScopeCalibrationProbe("control-0001", "anchor-in", "Sensorik-Nachbar"),
                        new ScopeCalibrationProbe("control-0002", "anchor-in", "Sensorik-Nachbar-2")),
                2), "");
        MapEmbedder embedder = fullEmbedder();
        embedder.byText.put("Sensorik-Nachbar-2", v(0.5f, 1, 0, 0.2f));
        ScopeSweepService service = new ScopeSweepService(
                new FixedGenerator(oneIsland), embedder, new MutableRevision());

        ScopeSweepOutcome outcome = service.run(plan());

        assertEquals(ScopeSweepOutcome.Status.CALIBRATION_WEAK, outcome.getStatus());
        assertEquals("the WEAK calibration stays explainable",
                ScopeFenceCalibrator.Confidence.WEAK, outcome.getCalibration().confidence);
        assertTrue(outcome.getDiagnostics().contains("coverage 1/2"));
    }

    @Test
    public void aScopeThatMovedDuringTheExpensiveGenerationIsStaleBeforeEmbedding() {
        FixedGenerator generator = new FixedGenerator(completeGeneration());
        final MutableRevision revision = new MutableRevision();
        generator.duringGeneration = new Runnable() {
            @Override
            public void run() {
                revision.revision = "rev-8"; // the user negotiated on while the model was busy
            }
        };
        MapEmbedder embedder = fullEmbedder();
        ScopeSweepService service = new ScopeSweepService(generator, embedder, revision);

        ScopeSweepOutcome outcome = service.run(plan());

        assertEquals(ScopeSweepOutcome.Status.STALE_SCOPE, outcome.getStatus());
        assertEquals(REVISION, outcome.getRequestedRevision());
        assertEquals("rev-8", outcome.getCurrentRevision());
    }

    @Test
    public void aMismatchedEmbeddingSnapshotIsRefusedBeforeAnyCosine() {
        ScopeSweepService service = new ScopeSweepService(
                new FixedGenerator(completeGeneration()),
                new MapEmbedder("other-model@2"), new MutableRevision());

        ScopeSweepOutcome outcome = service.run(plan());

        assertEquals("anchor vectors at one snapshot, probes at another = worthless cosines",
                ScopeSweepOutcome.Status.EMBEDDING_FAILED, outcome.getStatus());
        assertTrue(outcome.getDiagnostics().contains("other-model@2"));
    }

    @Test
    public void anEmbedderThatCannotServeTheBatchIsAnEmbeddingFailure() {
        MapEmbedder embedder = fullEmbedder();
        embedder.byText.remove("unbekannte Insel");
        ScopeSweepService service = new ScopeSweepService(
                new FixedGenerator(completeGeneration()), embedder, new MutableRevision());

        assertEquals(ScopeSweepOutcome.Status.EMBEDDING_FAILED,
                service.run(plan()).getStatus());
    }
}
