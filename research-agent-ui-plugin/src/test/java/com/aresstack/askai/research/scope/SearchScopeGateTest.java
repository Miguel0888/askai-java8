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
import static org.junit.Assert.fail;

/**
 * SC1 pins: the acquisition filter is SEMANTIC, not lexical — clear canonical OUT is the only
 * hard reject (a paraphrase without the blacklist word is caught, word presence alone never
 * rejects an IN/BOUNDARY candidate); IN/BOUNDARY/NOVEL pass in input order; no usable text is
 * conservatively UNCLASSIFIED without aborting the batch; a broken embedding batch throws
 * (fail-closed at the caller).
 */
public class SearchScopeGateTest {

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
                new AnchorVector("anchor-concept-scheduling", ScopeAnchor.Membership.IN,
                        new float[] {1f, 0f, 0f}),
                new AnchorVector("anchor-gui", ScopeAnchor.Membership.OUT,
                        new float[] {0f, 1f, 0f}));
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("anchor-concept-scheduling", "Scheduling");
        labels.put("anchor-gui", "GUI");
        return labels;
    }

    @Test
    public void clearCanonicalOutIsTheOnlyHardRejectAndOrderIsPreserved() {
        MappedEmbedder embedder = new MappedEmbedder();
        // A PARAPHRASE clearly on the OUT side — no blacklist word needed.
        embedder.byText.put("Desktop window toolkit tutorial", new float[] {0.05f, 0.98f, 0f});
        // Word presence alone: semantically IN despite mentioning the excluded term.
        embedder.byText.put("FreeRTOS Scheduling; one GUI example in section 8",
                new float[] {0.95f, 0.2f, 0f});
        embedder.byText.put("Grenzfall", new float[] {0.72f, 0.7f, 0f});
        embedder.byText.put("Neuland", new float[] {0f, 0f, 1f});

        List<SearchScopeGate.Decision> decisions = SearchScopeGate.evaluate(Arrays.asList(
                        new SearchScopeGate.Item("u1", "Desktop window toolkit tutorial"),
                        new SearchScopeGate.Item("u2",
                                "FreeRTOS Scheduling; one GUI example in section 8"),
                        new SearchScopeGate.Item("u3", "Grenzfall"),
                        new SearchScopeGate.Item("u4", "Neuland")),
                fence(), labels(), embedder, THRESHOLDS);

        assertEquals("one batch for the whole SERP", 1, embedder.calls);
        assertEquals(4, decisions.size());
        assertEquals("input order preserved", "u1", decisions.get(0).id);
        assertEquals(SearchScopeGate.Verdict.OUT, decisions.get(0).verdict);
        assertEquals("the OUT post is named for the log", "GUI",
                decisions.get(0).nearestOutLabel);
        assertEquals("word presence never rejects a semantically IN candidate",
                SearchScopeGate.Verdict.KEEP, decisions.get(1).verdict);
        assertEquals("BOUNDARY passes", SearchScopeGate.Verdict.KEEP,
                decisions.get(2).verdict);
        assertEquals("NOVEL passes", SearchScopeGate.Verdict.KEEP, decisions.get(3).verdict);
        assertNull(decisions.get(1).nearestOutLabel);
        // SC2a shadow: the IN side of the SAME reading — LIKELY_IN only, no new threshold.
        assertTrue("the clear IN keep reports its affinity", decisions.get(1).nearIn);
        assertEquals("Scheduling", decisions.get(1).nearestInLabel);
        assertTrue("BOUNDARY claims no affinity", !decisions.get(2).nearIn);
        assertTrue("NOVEL claims no affinity", !decisions.get(3).nearIn);
        assertTrue("OUT claims no affinity", !decisions.get(0).nearIn);
    }

    @Test
    public void noUsableTextIsUnclassifiedWithoutKillingTheBatch() {
        MappedEmbedder embedder = new MappedEmbedder();
        embedder.byText.put("Scheduling deep dive", new float[] {0.98f, 0.05f, 0f});

        List<SearchScopeGate.Decision> decisions = SearchScopeGate.evaluate(Arrays.asList(
                        new SearchScopeGate.Item("blank", "   "),
                        new SearchScopeGate.Item("ok", "Scheduling deep dive")),
                fence(), labels(), embedder, THRESHOLDS);

        assertEquals(SearchScopeGate.Verdict.UNCLASSIFIED, decisions.get(0).verdict);
        assertEquals(SearchScopeGate.Verdict.KEEP, decisions.get(1).verdict);
    }

    @Test
    public void aBrokenEmbeddingBatchThrowsForTheFailClosedCaller() {
        MappedEmbedder embedder = new MappedEmbedder();
        embedder.failWithNull = true;
        try {
            SearchScopeGate.evaluate(Arrays.asList(new SearchScopeGate.Item("u", "text")),
                    fence(), labels(), embedder, THRESHOLDS);
            fail("infrastructure failure must throw — never silently unfiltered");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("scope-control embedding"));
        }
    }
}
