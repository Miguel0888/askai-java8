package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * AP3 scope_probe pins: the four relations with their qualitative bands, concept-IN vs
 * blacklist-OUT authority, one batch for many terms, the independent per-term batch rule
 * (INVALID_TERM never kills the others), the settings-bounded drop, and the STALE_FENCE gate
 * that discards results when the fence moved while probing. The service is pure — there is
 * structurally nothing it could mutate.
 */
public class ScopeProbeServiceTest {

    private static final class MappedEmbedder implements ScopeSweepService.SweepEmbedder {
        final Map<String, float[]> byText = new HashMap<String, float[]>();
        int calls;
        boolean failWithNull;

        public String modelFingerprint() {
            return "test-embedder";
        }

        public List<float[]> embed(List<String> texts) {
            calls++;
            if (failWithNull) {
                return null;
            }
            List<float[]> vectors = new ArrayList<float[]>();
            for (String text : texts) {
                float[] vector = byText.get(text);
                vectors.add(vector == null ? new float[] {0f, 0f, 1f} : vector);
            }
            return vectors;
        }
    }

    private static final ScopeFenceEvaluator.Thresholds THRESHOLDS =
            new ScopeFenceEvaluator.Thresholds(0.6d, 0.2d);

    private static List<AnchorVector> fence() {
        return Arrays.asList(
                new AnchorVector("anchor-concept-tasks", ScopeAnchor.Membership.IN,
                        new float[] {1f, 0f, 0f}),
                new AnchorVector("anchor-guiux", ScopeAnchor.Membership.OUT,
                        new float[] {0f, 1f, 0f}));
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("anchor-concept-tasks", "Tasks und Scheduling");
        labels.put("anchor-guiux", "GUI/UX");
        return labels;
    }

    private static final ScopeProbeService.FenceFingerprint STEADY =
            new ScopeProbeService.FenceFingerprint() {
                public String current() {
                    return "5|e-1#7";
                }
            };

    private MappedEmbedder embedder() {
        MappedEmbedder embedder = new MappedEmbedder();
        embedder.byText.put("Priority Inversion", new float[] {0.95f, 0.1f, 0f});
        embedder.byText.put("Desktop GUI", new float[] {0.1f, 0.95f, 0f});
        embedder.byText.put("Grenzfall", new float[] {0.72f, 0.7f, 0f});
        embedder.byText.put("Neuland", new float[] {0f, 0f, 1f});
        return embedder;
    }

    @Test
    public void theFourRelationsCarryBandNearestAndCanonicalAuthority() {
        MappedEmbedder embedder = embedder();
        ScopeProbeService.Result result = ScopeProbeService.run(
                Arrays.asList("Priority Inversion", "Desktop GUI", "Grenzfall", "Neuland"),
                8, fence(), labels(), embedder, THRESHOLDS, "5|e-1#7", STEADY);

        assertEquals(ScopeProbeService.Status.OK, result.status);
        assertEquals("many terms, ONE embedding batch", 1, embedder.calls);
        assertEquals(4, result.readings.size());

        ScopeProbeService.TermReading in = result.readings.get(0);
        assertEquals(ScopeFenceEvaluator.Hint.LIKELY_IN, in.relation);
        assertEquals(ScopeProbeService.Band.CLEAR, in.band);
        assertEquals("Tasks und Scheduling", in.nearestAnchorLabel);
        assertEquals("the concept side is CANONICAL_IN", "CANONICAL_IN", in.authority);

        ScopeProbeService.TermReading out = result.readings.get(1);
        assertEquals(ScopeFenceEvaluator.Hint.LIKELY_OUT, out.relation);
        assertEquals(ScopeProbeService.Band.CLEAR, out.band);
        assertEquals("GUI/UX", out.nearestAnchorLabel);
        assertEquals("the blacklist side is CANONICAL_OUT", "CANONICAL_OUT", out.authority);

        ScopeProbeService.TermReading boundary = result.readings.get(2);
        assertEquals(ScopeFenceEvaluator.Hint.BOUNDARY, boundary.relation);
        assertEquals(ScopeProbeService.Band.MIXED, boundary.band);
        assertEquals("Tasks und Scheduling", boundary.nearestAnchorLabel);
        assertNull("BOUNDARY claims no side", boundary.authority);

        ScopeProbeService.TermReading novel = result.readings.get(3);
        assertEquals(ScopeFenceEvaluator.Hint.NOVEL, novel.relation);
        assertEquals(ScopeProbeService.Band.UNANCHORED, novel.band);
        assertNull(novel.nearestAnchorLabel);
        assertNull(novel.authority);

        String receipt = result.receipt();
        assertTrue(receipt.startsWith("PROBED terms=4"));
        assertTrue(receipt.contains("\"Priority Inversion\" -> LIKELY_IN band=CLEAR "
                + "nearest=\"Tasks und Scheduling\" authority=CANONICAL_IN"));
        assertTrue("no cosine ever reaches the receipt", !receipt.contains("0.9"));
        assertTrue(receipt.contains("This is an OBSERVATION only — nothing was changed."));

        List<String> log = result.logLines();
        assertEquals("scope_probe terms=4 -> LIKELY_IN=1 LIKELY_OUT=1 BOUNDARY=1 NOVEL=1",
                log.get(0));
        assertTrue(log.contains(
                "scope_probe \"Grenzfall\" -> BOUNDARY nearest=\"Tasks und Scheduling\""));
        assertTrue(log.contains("scope_probe \"Neuland\" -> NOVEL"));
    }

    @Test
    public void oneInvalidTermNeverKillsTheOthersAndTheLimitDropsHonestly() {
        ScopeProbeService.Result result = ScopeProbeService.run(
                Arrays.asList("  ", "Priority Inversion", "Desktop GUI", "Grenzfall"),
                2, fence(), labels(), embedder(), THRESHOLDS, "5|e-1#7", STEADY);

        assertEquals(ScopeProbeService.Status.OK, result.status);
        assertEquals("the two within the limit are judged", 2, result.readings.size());
        assertEquals(1, result.invalidTerms.size());
        assertEquals("no silent cap", 1, result.droppedOverLimit);
        assertTrue(result.receipt().contains("INVALID_TERM"));
        assertTrue(result.receipt().contains("DROPPED_OVER_LIMIT: 1 (max 2 terms per probe)"));
        assertTrue(result.logLines().get(0).contains("invalid=1"));
        assertTrue(result.logLines().get(0).contains("dropped=1"));
    }

    @Test
    public void aFenceThatMovedWhileProbingDiscardsEveryReading() {
        final int[] reads = {0};
        ScopeProbeService.FenceFingerprint moving = new ScopeProbeService.FenceFingerprint() {
            public String current() {
                reads[0]++;
                return "6|e-1#8"; // a card was added while the terms were embedding
            }
        };
        ScopeProbeService.Result result = ScopeProbeService.run(
                Arrays.asList("Priority Inversion"), 8, fence(), labels(), embedder(),
                THRESHOLDS, "5|e-1#7", moving);

        assertEquals(ScopeProbeService.Status.STALE_FENCE, result.status);
        assertTrue("never stale results dressed as current", result.readings.isEmpty());
        assertTrue(result.receipt().startsWith("STALE_FENCE"));
        assertEquals(Arrays.asList("scope_probe -> STALE_FENCE"), result.logLines());
        assertTrue("the fingerprint was actually re-read", reads[0] > 0);
    }

    @Test
    public void anEmbeddingFailureIsInfrastructureAndRejectsTheWholeSnapshot() {
        MappedEmbedder embedder = embedder();
        embedder.failWithNull = true;
        ScopeProbeService.Result result = ScopeProbeService.run(
                Arrays.asList("Priority Inversion", "Desktop GUI"), 8, fence(), labels(),
                embedder, THRESHOLDS, "5|e-1#7", STEADY);

        assertEquals(ScopeProbeService.Status.EMBEDDING_FAILED, result.status);
        assertTrue(result.readings.isEmpty());
        assertTrue(result.receipt().startsWith("PROBE_FAILED:"));
        assertTrue(result.logLines().get(0).startsWith("scope_probe -> PROBE_FAILED"));
    }
}
