package com.aresstack.askai.research.knowledge.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C5b builder: a deterministic FULL rebuild over the active corpus — the same corpus always yields the same
 * projection (fingerprints, topics, section order); distinct vector directions cluster apart; uncovered brief
 * questions surface as a visible gap section; an empty corpus is a valid empty projection.
 */
public class LiveOutlineProjectionBuilderTest {

    private static Passage passage(String id, String text) {
        return new Passage(id, "cap-1", Collections.singletonList(id + "#s"), "Root", text, "fpA",
                "seg-v1", "en");
    }

    private static EmbeddingPort.EmbeddingVector vector(float x, float y) {
        return new EmbeddingPort.EmbeddingVector("m", "fpA", new float[]{x, y});
    }

    /** Two clearly separated directions → two topics: display passages vs battery passages. */
    private static Object[] corpus() {
        List<Passage> passages = new ArrayList<Passage>(Arrays.asList(
                passage("cap-1#p0", "Waveguide displays project bright images."),
                passage("cap-1#p1", "Display brightness depends on the waveguide."),
                passage("cap-1#p2", "Battery life limits wearable runtime."),
                passage("cap-1#p3", "Battery chemistry improves wearable endurance.")));
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        vectors.put("cap-1#p0", vector(1f, 0f));
        vectors.put("cap-1#p1", vector(0.95f, 0.05f));
        vectors.put("cap-1#p2", vector(0f, 1f));
        vectors.put("cap-1#p3", vector(0.05f, 0.95f));
        return new Object[]{passages, vectors};
    }

    @SuppressWarnings("unchecked")
    @Test
    public void theSameCorpusRebuildsToTheIdenticalProjection() {
        Object[] c = corpus();
        LiveOutlineProjectionBuilder builder = new LiveOutlineProjectionBuilder();
        LiveOutlineProjection first = builder.build(1L, "fpA", 100L,
                (List<Passage>) c[0], (Map<String, EmbeddingPort.EmbeddingVector>) c[1],
                Collections.<String>emptyList());
        // Shuffled input order → the deterministic sort inside the builder normalizes it.
        List<Passage> shuffled = new ArrayList<Passage>((List<Passage>) c[0]);
        Collections.reverse(shuffled);
        LiveOutlineProjection second = builder.build(2L, "fpA", 200L, shuffled,
                (Map<String, EmbeddingPort.EmbeddingVector>) c[1], Collections.<String>emptyList());

        assertEquals(first.getCorpusFingerprint(), second.getCorpusFingerprint());
        assertEquals(first.getTopics().size(), second.getTopics().size());
        for (int i = 0; i < first.getTopics().size(); i++) {
            assertEquals("cluster identity is stable across rebuilds",
                    first.getTopics().get(i).getClusterId(), second.getTopics().get(i).getClusterId());
        }
        assertEquals(first.getSections().size(), second.getSections().size());
        assertEquals(2, first.getTopics().size()); // displays vs batteries
    }

    @SuppressWarnings("unchecked")
    @Test
    public void uncoveredBriefQuestionsSurfaceAsAVisibleGapSection() {
        Object[] c = corpus();
        LiveOutlineProjection projection = new LiveOutlineProjectionBuilder().build(1L, "fpA", 100L,
                (List<Passage>) c[0], (Map<String, EmbeddingPort.EmbeddingVector>) c[1],
                Arrays.asList("How do waveguide displays work?", "What about pricing and market share?"));
        LiveOutlineSection last = projection.getSections().get(projection.getSections().size() - 1);
        assertEquals("Open questions", last.getTitle());
        assertEquals("the display question is covered; the pricing question is not",
                Collections.singletonList("What about pricing and market share?"),
                last.getUncoveredQuestions());
    }

    @Test
    public void anEmptyCorpusYieldsAValidEmptyProjection() {
        LiveOutlineProjection projection = new LiveOutlineProjectionBuilder().build(5L, "fpA", 100L,
                Collections.<Passage>emptyList(),
                Collections.<String, EmbeddingPort.EmbeddingVector>emptyMap(),
                Collections.<String>emptyList());
        assertTrue(projection.getTopics().isEmpty());
        assertTrue(projection.getSections().isEmpty());
        assertEquals(5L, projection.getProjectionRevision());
        String markdown = LiveOutlineMarkdown.render(projection);
        assertTrue("visibly an outline projection", markdown.contains("# Outline"));
        assertTrue(markdown.contains("Keine freigegebene Gliederung"));
    }
}
