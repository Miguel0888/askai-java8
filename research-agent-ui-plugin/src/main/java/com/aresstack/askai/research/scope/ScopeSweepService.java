package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.DiverseProbeSelector;
import com.aresstack.askai.research.domain.scope.ProbeReading;
import com.aresstack.askai.research.domain.scope.ProbeSweepAnalyzer;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGeneration;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.aresstack.askai.research.domain.scope.ScopeSweepOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Z3b-3: the HOST-LED sweep orchestration — an APPLICATION service, deliberately NOT in
 * research-domain: it sequences external I/O (one LLM generation, one embedding batch) and
 * staleness policy around the pure domain mathematics (calibrator/analyzer/selector), which stay
 * where they are. One CALLABLE run ends in a TYPED {@link ScopeSweepOutcome}; nothing here
 * triggers automatically (WHEN a sweep runs is a Z4/policy decision — the live generator costs
 * 45-115s of real model time), and nothing mutates scope.
 * <p>
 * Snapshot discipline: the plan is built ONCE from one immutable draft snapshot and one immutable
 * embedding configuration snapshot. The {@link SweepEmbedder} MUST be constructed on that frozen
 * snapshot (e.g. over the session's frozen {@code EmbeddingEndpointDescriptor}) — binding by
 * construction is the guarantee; the fingerprint comparison in {@link #run} only catches
 * mis-wiring, it cannot close a hot-reload race that a mutable embedder would open. No exception
 * escapes the typed outcome: adapter/transport failures become GENERATION_FAILED /
 * EMBEDDING_FAILED, never a stack trace where a verdict should be.
 */
public final class ScopeSweepService {

    /**
     * Embeds the transient sweep texts. Implementations MUST be bound at construction to ONE
     * immutable embedding snapshot — a hot-reloadable delegate here would silently mix embedding
     * worlds mid-run.
     */
    public interface SweepEmbedder {
        /** The frozen snapshot fingerprint this embedder was constructed on. */
        String modelFingerprint();

        /** One batch; null or wrong-sized result reads as embedding failure, never as vectors. */
        List<float[]> embed(List<String> texts);
    }

    /** Supplies the CURRENT canonical scope revision — the staleness probe. */
    public interface ScopeRevisionProbe {
        long currentRevision();
    }

    /**
     * Everything a run needs, pinned ONCE at start from ONE draft snapshot + ONE embedding
     * snapshot. The constructor VALIDATES that the generation request and the anchor vectors
     * describe the same fence (same anchorId + membership sets) — four independently gathered
     * pieces from different snapshots must fail loudly here, because the revision check alone
     * cannot see a mixed-snapshot plan whose revision happens to still be current.
     */
    public static final class SweepPlan {
        public final long scopeRevision;
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

        public SweepPlan(long scopeRevision, String embeddingFingerprint,
                         ProbeGenerationRequest generationRequest,
                         List<AnchorVector> anchorVectors,
                         List<String> missionReferenceTexts,
                         ScopeFenceEvaluator.Thresholds fenceThresholds,
                         ScopeFenceCalibrator.CalibrationParameters calibrationParameters,
                         double boundaryMargin, double sweepNoveltyGap,
                         DiverseProbeSelector.Parameters selectorParameters) {
            if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
                throw new IllegalArgumentException("embeddingFingerprint must be pinned");
            }
            this.scopeRevision = scopeRevision;
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
            requireSameFence(generationRequest, this.anchorVectors);
        }

        /** The request and the vectors must describe the SAME fence — mixed snapshots fail loudly. */
        private static void requireSameFence(ProbeGenerationRequest request,
                                             List<AnchorVector> vectors) {
            Map<String, ScopeAnchor.Membership> requested =
                    new LinkedHashMap<String, ScopeAnchor.Membership>();
            for (ScopeAnchor anchor : request.getAnchors()) {
                requested.put(anchor.getAnchorId(), anchor.getMembership());
            }
            Map<String, ScopeAnchor.Membership> vectorized =
                    new LinkedHashMap<String, ScopeAnchor.Membership>();
            for (AnchorVector anchor : vectors) {
                vectorized.put(anchor.anchorId, anchor.membership);
            }
            if (!requested.equals(vectorized)) {
                throw new IllegalArgumentException(
                        "generation request and anchor vectors describe DIFFERENT fences: request="
                                + requested + " vectors=" + vectorized
                                + " — build the plan from ONE draft snapshot");
            }
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
        // Mis-wiring guard (the REAL invariant is construction-time binding of the embedder to the
        // frozen snapshot): anchors vectorized at one snapshot + probes at another = worthless.
        if (!embedder.modelFingerprint().equals(plan.embeddingFingerprint)) {
            return ScopeSweepOutcome.embeddingFailed("embedder serves fingerprint '"
                    + embedder.modelFingerprint() + "' but the plan pinned '"
                    + plan.embeddingFingerprint + "'");
        }

        ProbeGenerationResult generated;
        try {
            generated = generator.generate(plan.generationRequest);
        } catch (RuntimeException transport) {
            // A throwing adapter (wire client, transport) must not escape the typed contract.
            return ScopeSweepOutcome.generationFailed(
                    ProbeGenerationResult.Status.PROVIDER_FAILURE,
                    "generator threw: " + transport);
        }
        if (!generated.isOk()) {
            return ScopeSweepOutcome.generationFailed(generated.getStatus(),
                    generated.getMessage());
        }
        ProbeGeneration generation = generated.getGeneration();

        // The generation took real model time (live: 45-115s) — check staleness BEFORE spending
        // embedding work on a fence that may already have moved.
        long midRevision = revisionProbe.currentRevision();
        if (plan.scopeRevision != midRevision) {
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
        List<float[]> vectors;
        try {
            vectors = embedder.embed(texts);
        } catch (RuntimeException transport) {
            return ScopeSweepOutcome.embeddingFailed("embedder threw: " + transport);
        }
        if (vectors == null || vectors.size() != texts.size()) {
            return ScopeSweepOutcome.embeddingFailed("embedder returned "
                    + (vectors == null ? "null" : vectors.size() + " vectors")
                    + " for " + texts.size() + " texts");
        }
        int missionCount = plan.missionReferenceTexts.size();
        List<float[]> missionVectors = vectors.subList(0, missionCount);
        int broadCount = generation.getBroadProbes().size();

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

        // Final staleness check narrows the publish race; READY still CARRIES (revision,
        // fingerprint) because a consumer must be able to re-check later — the check here can
        // never fully close the window.
        long finalRevision = revisionProbe.currentRevision();
        if (plan.scopeRevision != finalRevision) {
            return ScopeSweepOutcome.staleScope(plan.scopeRevision, finalRevision);
        }
        return ScopeSweepOutcome.ready(plan.scopeRevision, plan.embeddingFingerprint,
                calibration, sweep, diverse, generated.getMessage());
    }
}
