package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGeneration;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Z3b-3: the HOST-LED sweep orchestration — one CALLABLE run over the whole chain
 * (generate → embed → calibrate → gate → sweep → diversify) that ends in a TYPED
 * {@link ScopeSweepOutcome}. Nothing here triggers automatically: WHEN a sweep runs is a Z4/policy
 * decision — this service only makes running one possible (the live generator costs 45-115s of
 * real model time; nobody fires that per draft-update behind the user's back).
 * <p>
 * The canonical scope draft and the anchor vector index stay with the HOST; the generator port may
 * be implemented by a same-process fake or by a thin wire client to the runtime process — this
 * orchestration cannot tell the difference and must not be able to. It never mutates scope.
 * <p>
 * Epistemic contract: a sweep that could not be trusted NEVER reads as "no holes found". Every
 * non-READY outcome names its reason (generation failed / broad sample incomplete / calibration
 * weak / embedding failed / stale scope) — turning "I could not check the fence reliably" into
 * "the fence has no holes" is exactly the error all the gates exist to prevent.
 */
public final class ScopeSweepService {

    /** Embeds the transient sweep texts — MUST serve exactly one embedding snapshot. */
    public interface SweepEmbedder {
        /** The model fingerprint this embedder serves; compared against the plan's pinned one. */
        String modelFingerprint();

        /** One batch; null or wrong-sized result reads as embedding failure, never as vectors. */
        List<float[]> embed(List<String> texts);
    }

    /** Supplies the CURRENT canonical scope revision — the staleness probe. */
    public interface ScopeRevisionProbe {
        String currentRevision();
    }

    /**
     * Everything a run needs, pinned ONCE at start: the scope revision, the embedding snapshot
     * fingerprint, the anchor vectors (from the host's index, already at that fingerprint), the
     * mission reference texts and every explicit parameter. Nothing is re-read mid-run — the run
     * computes on (revision, fingerprint) or it does not publish.
     */
    public static final class SweepPlan {
        public final String scopeRevision;
        public final String embeddingFingerprint;
        public final ProbeGenerationRequest generationRequest;
        public final List<AnchorVector> anchorVectors;
        public final List<String> missionReferenceTexts;
        public final ScopeFenceEvaluator.Thresholds fenceThresholds;
        public final ScopeFenceCalibrator.CalibrationParameters calibrationParameters;
        /** The sweep's non-calibrated knobs; the floors come from the calibration. */
        public final double boundaryMargin;
        public final double sweepNoveltyGap;
        public final DiverseProbeSelector.Parameters selectorParameters;

        public SweepPlan(String scopeRevision, String embeddingFingerprint,
                         ProbeGenerationRequest generationRequest,
                         List<AnchorVector> anchorVectors,
                         List<String> missionReferenceTexts,
                         ScopeFenceEvaluator.Thresholds fenceThresholds,
                         ScopeFenceCalibrator.CalibrationParameters calibrationParameters,
                         double boundaryMargin, double sweepNoveltyGap,
                         DiverseProbeSelector.Parameters selectorParameters) {
            if (scopeRevision == null || scopeRevision.trim().isEmpty()) {
                throw new IllegalArgumentException("scopeRevision must be pinned");
            }
            if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
                throw new IllegalArgumentException("embeddingFingerprint must be pinned");
            }
            this.scopeRevision = scopeRevision.trim();
            this.embeddingFingerprint = embeddingFingerprint.trim();
            this.generationRequest = generationRequest;
            this.anchorVectors = Collections.unmodifiableList(
                    new ArrayList<AnchorVector>(anchorVectors));
            this.missionReferenceTexts = Collections.unmodifiableList(
                    new ArrayList<String>(missionReferenceTexts));
            this.fenceThresholds = fenceThresholds;
            this.calibrationParameters = calibrationParameters;
            this.boundaryMargin = boundaryMargin;
            this.sweepNoveltyGap = sweepNoveltyGap;
            this.selectorParameters = selectorParameters;
        }
    }

    private final ScopeProbeGenerator generator;
    private final SweepEmbedder embedder;
    private final ScopeRevisionProbe revisionProbe;

    public ScopeSweepService(ScopeProbeGenerator generator, SweepEmbedder embedder,
                             ScopeRevisionProbe revisionProbe) {
        if (generator == null || embedder == null || revisionProbe == null) {
            throw new IllegalArgumentException("generator, embedder and revisionProbe are required");
        }
        this.generator = generator;
        this.embedder = embedder;
        this.revisionProbe = revisionProbe;
    }

    public ScopeSweepOutcome run(SweepPlan plan) {
        // Invariant 2 first: the anchors were vectorized at the pinned fingerprint — computing
        // probe cosines against a DIFFERENT embedding snapshot is professionally worthless.
        if (!embedder.modelFingerprint().equals(plan.embeddingFingerprint)) {
            return ScopeSweepOutcome.embeddingFailed("embedder serves fingerprint '"
                    + embedder.modelFingerprint() + "' but the plan pinned '"
                    + plan.embeddingFingerprint + "'");
        }

        ProbeGenerationResult generated = generator.generate(plan.generationRequest);
        if (!generated.isOk()) {
            return ScopeSweepOutcome.generationFailed(generated.getStatus(),
                    generated.getMessage());
        }
        ProbeGeneration generation = generated.getGeneration();

        // The generation took real model time (live: 45-115s) — check staleness BEFORE spending
        // embedding work on a fence that may already have moved.
        String midRevision = revisionProbe.currentRevision();
        if (!plan.scopeRevision.equals(midRevision)) {
            return ScopeSweepOutcome.staleScope(plan.scopeRevision, midRevision);
        }

        if (!generation.broadSampleComplete()) {
            return ScopeSweepOutcome.broadSampleIncomplete(
                    generation.getRequestedBroadCount(), generation.getAcceptedBroadCount(),
                    generated.getMessage());
        }

        // ONE embedding batch for everything transient: mission references, broad probes, controls.
        List<String> texts = new ArrayList<String>();
        texts.addAll(plan.missionReferenceTexts);
        for (ScopeProbe probe : generation.getBroadProbes()) {
            texts.add(probe.getSemanticText());
        }
        for (ScopeCalibrationProbe control : generation.getCalibrationProbes()) {
            texts.add(control.getSemanticText());
        }
        List<float[]> vectors = embedder.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            return ScopeSweepOutcome.embeddingFailed("embedder returned "
                    + (vectors == null ? "null" : vectors.size() + " vectors")
                    + " for " + texts.size() + " texts");
        }
        int missionCount = plan.missionReferenceTexts.size();
        List<float[]> missionVectors = vectors.subList(0, missionCount);
        int broadCount = generation.getBroadProbes().size();

        Map<String, float[]> anchorVectorsById = new LinkedHashMap<String, float[]>();
        for (AnchorVector anchor : plan.anchorVectors) {
            anchorVectorsById.put(anchor.anchorId, vectorOf(anchor));
        }
        List<ScopeFenceCalibrator.CalibrationProbeVector> controls =
                new ArrayList<ScopeFenceCalibrator.CalibrationProbeVector>();
        for (int index = 0; index < generation.getCalibrationProbes().size(); index++) {
            controls.add(new ScopeFenceCalibrator.CalibrationProbeVector(
                    generation.getCalibrationProbes().get(index),
                    vectors.get(missionCount + broadCount + index)));
        }
        ScopeFenceCalibrator.Samples samples = ScopeFenceCalibrator.measure(
                plan.anchorVectors, missionVectors, controls);
        ScopeFenceCalibrator.FenceCalibration calibration =
                ScopeFenceCalibrator.calibrate(samples, plan.calibrationParameters);
        if (!calibration.permitsHoleHunting()) {
            return ScopeSweepOutcome.calibrationWeak(calibration);
        }

        List<ProbeSweepAnalyzer.ProbeVector> probeVectors =
                new ArrayList<ProbeSweepAnalyzer.ProbeVector>();
        for (int index = 0; index < broadCount; index++) {
            probeVectors.add(new ProbeSweepAnalyzer.ProbeVector(
                    generation.getBroadProbes().get(index), vectors.get(missionCount + index)));
        }
        ProbeSweepAnalyzer.ProbeSweepResult sweep = ProbeSweepAnalyzer.analyze(
                probeVectors, missionVectors,
                new ScopeFenceEvaluator(plan.anchorVectors), plan.fenceThresholds,
                new ProbeSweepAnalyzer.SweepParameters(calibration.minimumMissionRelevance,
                        plan.boundaryMargin, plan.sweepNoveltyGap, calibration.knownRegionFloor));
        List<ProbeReading> diverse = DiverseProbeSelector.select(sweep.interesting(),
                ProbeSweepAnalyzer.vectorsById(probeVectors), plan.selectorParameters);

        // Invariant 1, before publishing: the whole run computed on revision R — if the canonical
        // draft moved meanwhile, these readings describe a fence that no longer exists.
        String finalRevision = revisionProbe.currentRevision();
        if (!plan.scopeRevision.equals(finalRevision)) {
            return ScopeSweepOutcome.staleScope(plan.scopeRevision, finalRevision);
        }
        return ScopeSweepOutcome.ready(calibration, sweep, diverse, generated.getMessage());
    }

    private static float[] vectorOf(AnchorVector anchor) {
        return anchor.vector;
    }
}
