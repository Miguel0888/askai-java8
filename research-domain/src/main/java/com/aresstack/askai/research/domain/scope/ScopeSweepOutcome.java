package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TYPED end of one sweep run. The non-READY states are first-class facts, never an empty
 * result list: "I could not check the fence reliably" (generation failed, sample incomplete,
 * calibration weak, embedding failed, scope moved) must stay distinguishable from "the fence has
 * no holes" — collapsing them is the epistemic error the whole gate chain exists to prevent.
 * READY exists ONLY when generation was OK and complete, the calibration permits hole hunting and
 * the scope revision is still current.
 */
public final class ScopeSweepOutcome {

    public enum Status {
        READY,
        GENERATION_FAILED,
        BROAD_SAMPLE_INCOMPLETE,
        CALIBRATION_WEAK,
        EMBEDDING_FAILED,
        STALE_SCOPE
    }

    private final Status status;
    /** READY + CALIBRATION_WEAK carry the calibration (WEAK keeps its samples explainable). */
    private final ScopeFenceCalibrator.FenceCalibration calibration;
    private final ProbeSweepAnalyzer.ProbeSweepResult sweep;
    private final List<ProbeReading> diverseCandidates;
    private final ScopeProbeGenerator.ProbeGenerationResult.Status generatorStatus;
    private final int requestedBroadCount;
    private final int acceptedBroadCount;
    private final String requestedRevision;
    private final String currentRevision;
    private final String diagnostics;

    private ScopeSweepOutcome(Status status,
                              ScopeFenceCalibrator.FenceCalibration calibration,
                              ProbeSweepAnalyzer.ProbeSweepResult sweep,
                              List<ProbeReading> diverseCandidates,
                              ScopeProbeGenerator.ProbeGenerationResult.Status generatorStatus,
                              int requestedBroadCount, int acceptedBroadCount,
                              String requestedRevision, String currentRevision,
                              String diagnostics) {
        this.status = status;
        this.calibration = calibration;
        this.sweep = sweep;
        this.diverseCandidates = diverseCandidates == null
                ? Collections.<ProbeReading>emptyList()
                : Collections.unmodifiableList(new ArrayList<ProbeReading>(diverseCandidates));
        this.generatorStatus = generatorStatus;
        this.requestedBroadCount = requestedBroadCount;
        this.acceptedBroadCount = acceptedBroadCount;
        this.requestedRevision = requestedRevision == null ? "" : requestedRevision;
        this.currentRevision = currentRevision == null ? "" : currentRevision;
        this.diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public static ScopeSweepOutcome ready(ScopeFenceCalibrator.FenceCalibration calibration,
                                          ProbeSweepAnalyzer.ProbeSweepResult sweep,
                                          List<ProbeReading> diverseCandidates,
                                          String diagnostics) {
        if (calibration == null || sweep == null) {
            throw new IllegalArgumentException("READY carries calibration and sweep");
        }
        return new ScopeSweepOutcome(Status.READY, calibration, sweep, diverseCandidates,
                null, 0, 0, "", "", diagnostics);
    }

    public static ScopeSweepOutcome generationFailed(
            ScopeProbeGenerator.ProbeGenerationResult.Status generatorStatus, String diagnostics) {
        return new ScopeSweepOutcome(Status.GENERATION_FAILED, null, null, null,
                generatorStatus, 0, 0, "", "", diagnostics);
    }

    public static ScopeSweepOutcome broadSampleIncomplete(int requested, int accepted,
                                                          String diagnostics) {
        return new ScopeSweepOutcome(Status.BROAD_SAMPLE_INCOMPLETE, null, null, null,
                null, requested, accepted, "", "", diagnostics);
    }

    public static ScopeSweepOutcome calibrationWeak(
            ScopeFenceCalibrator.FenceCalibration calibration) {
        return new ScopeSweepOutcome(Status.CALIBRATION_WEAK, calibration, null, null,
                null, 0, 0, "", "",
                "coverage " + calibration.samples.distinctParentAnchorsCovered + "/"
                        + calibration.samples.eligibleAnchorCount + ", neighbor samples "
                        + calibration.samples.anchorNeighborSimilarities.size()
                        + ", negotiated " + calibration.samples.anchorMissionRelevances.size());
    }

    public static ScopeSweepOutcome embeddingFailed(String diagnostics) {
        return new ScopeSweepOutcome(Status.EMBEDDING_FAILED, null, null, null,
                null, 0, 0, "", "", diagnostics);
    }

    public static ScopeSweepOutcome staleScope(String requestedRevision, String currentRevision) {
        return new ScopeSweepOutcome(Status.STALE_SCOPE, null, null, null,
                null, 0, 0, requestedRevision, currentRevision,
                "scope moved from " + requestedRevision + " to " + currentRevision);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    public ScopeFenceCalibrator.FenceCalibration getCalibration() {
        return calibration;
    }

    public ProbeSweepAnalyzer.ProbeSweepResult getSweep() {
        return sweep;
    }

    public List<ProbeReading> getDiverseCandidates() {
        return diverseCandidates;
    }

    public ScopeProbeGenerator.ProbeGenerationResult.Status getGeneratorStatus() {
        return generatorStatus;
    }

    public int getRequestedBroadCount() {
        return requestedBroadCount;
    }

    public int getAcceptedBroadCount() {
        return acceptedBroadCount;
    }

    public String getRequestedRevision() {
        return requestedRevision;
    }

    public String getCurrentRevision() {
        return currentRevision;
    }

    public String getDiagnostics() {
        return diagnostics;
    }
}
