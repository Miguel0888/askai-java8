package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AP3 {@code scope_probe}: the READ-ONLY semantic sensor around the existing
 * {@link ScopeFenceEvaluator} geometry — no new sweep, no second calibrator, no new fence
 * mathematics. For a list of model-named terms it answers "where does this sit relative to the
 * negotiated fence", and NOTHING else: it decides nothing, mutates nothing, chooses no question
 * and never talks to the user.
 *
 * <p>Authority follows the simplified two-artifact model: Concept/Mindmap posts are
 * {@code CANONICAL_IN}, blacklist posts are {@code CANONICAL_OUT}. PROVISIONAL remains a core
 * backcompat detail and is deliberately NOT exposed as a third truth here. The confidence band
 * is QUALITATIVE on purpose ({@code CLEAR}/{@code MIXED}/{@code UNANCHORED}) — the cosine fence
 * does not support percentage claims, so none are made; raw cosines stay out of the receipt.</p>
 *
 * <p>Batch rule (like add_cards): every term is judged independently — one empty term costs one
 * {@code INVALID_TERM} line, never the batch. Only infrastructure (embedding failure) or a fence
 * that changed while probing ({@code STALE_FENCE}, checked against the pinned fingerprint before
 * returning) rejects the whole snapshot.</p>
 */
public final class ScopeProbeService {

    /** The effective-fence fingerprint (scope revision + concept epoch#revision), re-readable. */
    public interface FenceFingerprint {
        String current();
    }

    /** Qualitative confidence — never a probability, never a percentage. */
    public enum Band { CLEAR, MIXED, UNANCHORED }

    public enum Status { OK, STALE_FENCE, EMBEDDING_FAILED }

    /** One term's observation. {@code nearestAnchorLabel}/{@code authority} may be null (NOVEL). */
    public static final class TermReading {
        public final String term;
        public final ScopeFenceEvaluator.Hint relation;
        public final Band band;
        public final String nearestAnchorLabel;
        public final String authority;

        TermReading(String term, ScopeFenceEvaluator.Hint relation, Band band,
                    String nearestAnchorLabel, String authority) {
            this.term = term;
            this.relation = relation;
            this.band = band;
            this.nearestAnchorLabel = nearestAnchorLabel;
            this.authority = authority;
        }
    }

    /** The typed probe outcome: readings only when the fence held still the whole time. */
    public static final class Result {
        public final Status status;
        public final List<TermReading> readings;
        public final List<String> invalidTerms;
        public final int droppedOverLimit;
        public final int maxTerms;
        public final String detail;

        Result(Status status, List<TermReading> readings, List<String> invalidTerms,
               int droppedOverLimit, int maxTerms, String detail) {
            this.status = status;
            this.readings = Collections.unmodifiableList(readings);
            this.invalidTerms = Collections.unmodifiableList(invalidTerms);
            this.droppedOverLimit = droppedOverLimit;
            this.maxTerms = maxTerms;
            this.detail = detail;
        }

        /** The model-facing receipt (machine format — the model narrates, this never does). */
        public String receipt() {
            if (status == Status.STALE_FENCE) {
                return "STALE_FENCE — the scope changed while probing; the results were "
                        + "discarded, none of them describe the current fence.";
            }
            if (status == Status.EMBEDDING_FAILED) {
                return "PROBE_FAILED: " + detail;
            }
            StringBuilder sb = new StringBuilder("PROBED terms=").append(readings.size());
            for (TermReading reading : readings) {
                sb.append('\n').append('"').append(reading.term).append("\" -> ")
                        .append(reading.relation.name())
                        .append(" band=").append(reading.band.name());
                if (reading.nearestAnchorLabel != null) {
                    sb.append(" nearest=\"").append(reading.nearestAnchorLabel).append('"');
                }
                if (reading.authority != null) {
                    sb.append(" authority=").append(reading.authority);
                }
            }
            for (String invalid : invalidTerms) {
                sb.append("\nINVALID_TERM: \"").append(invalid).append('"');
            }
            if (droppedOverLimit > 0) {
                sb.append("\nDROPPED_OVER_LIMIT: ").append(droppedOverLimit)
                        .append(" (max ").append(maxTerms).append(" terms per probe)");
            }
            // AP3 retest finding: the sensor measured correctly, the NARRATION inverted the
            // semantics afterwards ("NOVEL = besonders relevant", a CANONICAL_OUT reopened as
            // "potenzieller Restbereich"). Drill prose did not bind — the hard meaning rules
            // ride ON the receipt, derived per case from the ACTUAL readings.
            boolean hasNovel = false;
            boolean hasBoundary = false;
            boolean hasCanonicalOut = false;
            for (TermReading reading : readings) {
                hasNovel |= reading.relation == ScopeFenceEvaluator.Hint.NOVEL;
                hasBoundary |= reading.relation == ScopeFenceEvaluator.Hint.BOUNDARY;
                hasCanonicalOut |= "CANONICAL_OUT".equals(reading.authority);
            }
            if (!readings.isEmpty()) {
                sb.append("\nMEANING — hard rules for your reply:");
                if (hasNovel) {
                    sb.append("\n- NOVEL means: not anchored in the negotiated scope yet — "
                            + "neither in nor out. NEVER call it relevant, important or "
                            + "recommended.");
                }
                if (hasBoundary) {
                    sb.append("\n- BOUNDARY means: genuinely unclear which side it belongs "
                            + "to.");
                }
                if (hasCanonicalOut) {
                    sb.append("\n- LIKELY_OUT with authority=CANONICAL_OUT means: the USER "
                            + "already ruled this out. State it as settled. Never reopen it, "
                            + "never call it a possible remaining area, ask nothing about "
                            + "it.");
                }
                // AP3 retest 3: the model summarized correctly but skipped the required
                // membership question even with the directive as its immediate input — the
                // ONE follow-up question is HOST-appended now (mechanical, derived from the
                // readings), so it no longer depends on the small model's obedience.
                sb.append("\n- Do not ask any follow-up question yourself — the application "
                        + "appends the single question (or none) derived from this "
                        + "measurement. You only summarize.");
            }
            sb.append("\nThis is an OBSERVATION only — nothing was changed.");
            return sb.toString();
        }

        /** The technical-log projection: one summary line, one line per interesting reading. */
        public List<String> logLines() {
            List<String> lines = new ArrayList<String>();
            if (status == Status.STALE_FENCE) {
                lines.add("scope_probe -> STALE_FENCE");
                return lines;
            }
            if (status == Status.EMBEDDING_FAILED) {
                lines.add("scope_probe -> PROBE_FAILED " + detail);
                return lines;
            }
            int in = 0;
            int out = 0;
            int boundary = 0;
            int novel = 0;
            for (TermReading reading : readings) {
                switch (reading.relation) {
                    case LIKELY_IN: in++; break;
                    case LIKELY_OUT: out++; break;
                    case BOUNDARY: boundary++; break;
                    default: novel++;
                }
            }
            StringBuilder summary = new StringBuilder("scope_probe terms=")
                    .append(readings.size())
                    .append(" -> LIKELY_IN=").append(in).append(" LIKELY_OUT=").append(out)
                    .append(" BOUNDARY=").append(boundary).append(" NOVEL=").append(novel);
            if (!invalidTerms.isEmpty()) {
                summary.append(" invalid=").append(invalidTerms.size());
            }
            if (droppedOverLimit > 0) {
                summary.append(" dropped=").append(droppedOverLimit);
            }
            lines.add(summary.toString());
            for (TermReading reading : readings) {
                if (reading.relation == ScopeFenceEvaluator.Hint.BOUNDARY
                        || reading.relation == ScopeFenceEvaluator.Hint.NOVEL) {
                    lines.add("scope_probe \"" + reading.term + "\" -> "
                            + reading.relation.name()
                            + (reading.nearestAnchorLabel == null ? ""
                                    : " nearest=\"" + reading.nearestAnchorLabel + "\""));
                }
            }
            return lines;
        }
    }

    private ScopeProbeService() {
    }

    /**
     * One probe run over ONE pinned snapshot. The caller pins the fingerprint BEFORE gathering
     * the anchors; this method re-reads it after the embedding work — a fence that moved in
     * between yields {@code STALE_FENCE} with no readings, never stale results dressed as
     * current ones.
     */
    public static Result run(List<String> rawTerms, int maxTerms,
                             List<AnchorVector> anchorVectors,
                             Map<String, String> anchorLabelsById,
                             ScopeSweepService.SweepEmbedder embedder,
                             ScopeFenceEvaluator.Thresholds thresholds,
                             String pinnedFingerprint, FenceFingerprint fence) {
        List<String> valid = new ArrayList<String>();
        List<String> invalid = new ArrayList<String>();
        for (String raw : rawTerms == null ? Collections.<String>emptyList() : rawTerms) {
            String term = raw == null ? "" : raw.trim();
            if (term.isEmpty()) {
                invalid.add(raw == null ? "" : raw);
            } else {
                valid.add(term);
            }
        }
        int dropped = 0;
        if (valid.size() > maxTerms) {
            dropped = valid.size() - maxTerms;
            valid = new ArrayList<String>(valid.subList(0, maxTerms));
        }
        List<TermReading> readings = new ArrayList<TermReading>();
        if (!valid.isEmpty()) {
            List<float[]> vectors;
            try {
                vectors = embedder.embed(valid);
            } catch (RuntimeException transport) {
                return new Result(Status.EMBEDDING_FAILED, readings, invalid, dropped,
                        maxTerms, "embedder threw: " + transport);
            }
            if (vectors == null || vectors.size() != valid.size()) {
                return new Result(Status.EMBEDDING_FAILED, readings, invalid, dropped,
                        maxTerms, "embedder returned "
                        + (vectors == null ? "null" : vectors.size() + " vectors")
                        + " for " + valid.size() + " terms");
            }
            ScopeFenceEvaluator evaluator = new ScopeFenceEvaluator(anchorVectors);
            for (int index = 0; index < valid.size(); index++) {
                readings.add(readingOf(valid.get(index),
                        evaluator.evaluate(vectors.get(index), thresholds), anchorLabelsById));
            }
        }
        // The stale gate LAST: a parallel concept/blacklist change must never let seemingly
        // valid probe results describe an old fence.
        if (!fence.current().equals(pinnedFingerprint)) {
            return new Result(Status.STALE_FENCE, new ArrayList<TermReading>(), invalid,
                    dropped, maxTerms, null);
        }
        return new Result(Status.OK, readings, invalid, dropped, maxTerms, null);
    }

    /** Map one geometric reading to the ratified observation shape. */
    private static TermReading readingOf(String term, ScopeFenceEvaluator.Reading reading,
                                         Map<String, String> labelsById) {
        Band band;
        String nearestId;
        String authority;
        switch (reading.hint) {
            case LIKELY_IN:
                band = Band.CLEAR;
                nearestId = reading.nearestInAnchorId;
                authority = "CANONICAL_IN";
                break;
            case LIKELY_OUT:
                band = Band.CLEAR;
                nearestId = reading.nearestOutAnchorId;
                authority = "CANONICAL_OUT";
                break;
            case BOUNDARY:
                band = Band.MIXED;
                nearestId = reading.nearestInSimilarity >= reading.nearestOutSimilarity
                        ? reading.nearestInAnchorId : reading.nearestOutAnchorId;
                authority = null;
                break;
            default:
                band = Band.UNANCHORED;
                nearestId = null;
                authority = null;
        }
        String label = null;
        if (nearestId != null) {
            label = labelsById.get(nearestId);
            if (label == null || label.trim().isEmpty()) {
                label = nearestId;
            }
        }
        return new TermReading(term, reading.hint, band, label, authority);
    }
}
