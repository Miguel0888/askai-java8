package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Z3's hole finder: runs a batch of embedded probes against the coarse mission frame AND the fence —
 * two orthogonal axes measured separately, then bucketed advisory. The fence relationship comes
 * EXCLUSIVELY from {@link ScopeFenceEvaluator} (Z2 stays exactly as small as it is); the mission
 * relevance from the draft's mission/domains/contexts reference texts. Mission relevance never
 * decides IN/OUT — a consumer fitness tracker can be highly relevant to "Wearables" and still be
 * EXCLUDED by a negotiated OUT post.
 * <p>
 * There is NO absolute novelty cutoff and NO coverage percentage here: a sweep with zero UNEXPLORED
 * findings means only "this sweep found nothing new" — saturation EVIDENCE grows over several broad
 * sweeps, it is never computed as a magic number.
 */
public final class ProbeSweepAnalyzer {

    /** One embedded probe — the sweep's working unit (vectors come from one batch embed call). */
    public static final class ProbeVector {
        public final ScopeProbe probe;
        final float[] vector;

        public ProbeVector(ScopeProbe probe, float[] vector) {
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

    /** The whole sweep, raw readings first — counts are a FINDING, never a completion verdict. */
    public static final class ProbeSweepResult {
        private final List<ProbeReading> readings;
        private final Map<ProbeReading.Category, Integer> counts;

        ProbeSweepResult(List<ProbeReading> readings) {
            this.readings = Collections.unmodifiableList(readings);
            Map<ProbeReading.Category, Integer> tally =
                    new EnumMap<ProbeReading.Category, Integer>(ProbeReading.Category.class);
            for (ProbeReading reading : readings) {
                Integer sofar = tally.get(reading.getCategory());
                tally.put(reading.getCategory(), sofar == null ? 1 : sofar + 1);
            }
            this.counts = Collections.unmodifiableMap(tally);
        }

        public List<ProbeReading> getReadings() {
            return readings;
        }

        public int countOf(ProbeReading.Category category) {
            Integer count = counts.get(category);
            return count == null ? 0 : count;
        }

        /** The question-worthy pool: UNEXPLORED and BOUNDARY probes, in reading order. */
        public List<ProbeReading> interesting() {
            List<ProbeReading> pool = new ArrayList<ProbeReading>();
            for (ProbeReading reading : readings) {
                if (reading.getCategory() == ProbeReading.Category.UNEXPLORED
                        || reading.getCategory() == ProbeReading.Category.BOUNDARY) {
                    pool.add(reading);
                }
            }
            return pool;
        }
    }

    private ProbeSweepAnalyzer() {
    }

    /**
     * Measure every probe on both axes and bucket it.
     *
     * @param probes                  the embedded probes of ONE sweep (one batch, one model)
     * @param missionReferenceVectors embedded mission/domains/contexts texts — the coarse frame
     * @param fence                   the Z2 evaluator over the CURRENT anchors
     * @param fenceThresholds         the explicit Z2 classification cut-offs
     * @param minimumMissionRelevance EXPLICIT floor under which a probe is IRRELEVANT — a
     *                                calibration parameter of the caller, never a hidden constant
     */
    public static ProbeSweepResult analyze(List<ProbeVector> probes,
                                           List<float[]> missionReferenceVectors,
                                           ScopeFenceEvaluator fence,
                                           ScopeFenceEvaluator.Thresholds fenceThresholds,
                                           double minimumMissionRelevance) {
        List<ProbeReading> readings = new ArrayList<ProbeReading>();
        for (ProbeVector probe : probes) {
            double relevance = 0.0d;
            for (float[] reference : missionReferenceVectors) {
                relevance = Math.max(relevance, ScopeFenceEvaluator.cosine(probe.vector, reference));
            }
            ScopeFenceEvaluator.Reading fenceReading = fence.evaluate(probe.vector, fenceThresholds);
            ProbeReading.Category category = relevance < minimumMissionRelevance
                    ? ProbeReading.Category.IRRELEVANT
                    : categoryOf(fenceReading.hint);
            readings.add(new ProbeReading(probe.probe, relevance, fenceReading, category));
        }
        return new ProbeSweepResult(readings);
    }

    /** The advisory bucket of a mission-RELEVANT probe follows the fence hint one-to-one. */
    private static ProbeReading.Category categoryOf(ScopeFenceEvaluator.Hint hint) {
        switch (hint) {
            case LIKELY_IN:
                return ProbeReading.Category.KNOWN;
            case LIKELY_OUT:
                return ProbeReading.Category.EXCLUDED;
            case BOUNDARY:
                return ProbeReading.Category.BOUNDARY;
            default:
                return ProbeReading.Category.UNEXPLORED;
        }
    }

    /** The probe vectors by id — what the diversity selection needs to compare candidates. */
    public static Map<String, float[]> vectorsById(List<ProbeVector> probes) {
        Map<String, float[]> byId = new LinkedHashMap<String, float[]>();
        for (ProbeVector probe : probes) {
            byId.put(probe.probe.getProbeId(), probe.vector);
        }
        return byId;
    }
}
