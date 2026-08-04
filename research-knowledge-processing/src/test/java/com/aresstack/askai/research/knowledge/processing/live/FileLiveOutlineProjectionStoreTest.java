package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.knowledge.live.LiveOutlineProjection;
import com.aresstack.askai.research.knowledge.live.LiveOutlineSection;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * C5a: the live outline projection is a REBUILDABLE persisted artifact — lossless round-trip, deterministic
 * cluster/corpus identities, and a missing/corrupt file reads as {@code null} (→ rebuild, never a failure).
 */
public class FileLiveOutlineProjectionStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static LiveOutlineProjection sample() {
        LiveTopicProjection topic = new LiveTopicProjection(
                Arrays.asList("cap-1#p0@v-f", "cap-2#p3@v-f"),
                Collections.singletonList("cap-1#p0@v-f"),
                "Wearable displays", 0.87);
        LiveOutlineSection section = new LiveOutlineSection("sec-" + topic.getClusterId(),
                "Wearable displays", "", Collections.singletonList(topic.getClusterId()),
                Arrays.asList("cap-1#p0@v-f", "cap-2#p3@v-f"),
                Arrays.asList("How do waveguides work, exactly?", "Cost, availability?"));
        return new LiveOutlineProjection(3L,
                LiveOutlineProjection.corpusFingerprintOf(Arrays.asList("cap-1#p0@v-f", "cap-2#p3@v-f")),
                "fpA", 1234L, Collections.singletonList(topic), Collections.singletonList(section));
    }

    @Test
    public void roundTripsLosslessly() throws Exception {
        File dir = folder.newFolder("proj");
        FileLiveOutlineProjectionStore store = new FileLiveOutlineProjectionStore(dir);
        LiveOutlineProjection saved = sample();
        store.save(saved);

        LiveOutlineProjection back = new FileLiveOutlineProjectionStore(dir).load();
        assertEquals(3L, back.getProjectionRevision());
        assertEquals(saved.getCorpusFingerprint(), back.getCorpusFingerprint());
        assertEquals("fpA", back.getEmbeddingFingerprint());
        assertEquals(1, back.getTopics().size());
        assertEquals(saved.getTopics().get(0).getClusterId(), back.getTopics().get(0).getClusterId());
        assertEquals("Wearable displays", back.getTopics().get(0).getTitle());
        assertEquals(0.87, back.getTopics().get(0).getConfidence(), 1e-9);
        assertEquals(Arrays.asList("cap-1#p0@v-f", "cap-2#p3@v-f"),
                back.getTopics().get(0).getMemberPassageIds());
        assertEquals(1, back.getSections().size());
        LiveOutlineSection s = back.getSections().get(0);
        assertEquals("Wearable displays", s.getTitle());
        assertEquals(Arrays.asList("How do waveguides work, exactly?", "Cost, availability?"),
                s.getUncoveredQuestions()); // commas in free text survive (tab-escaped)
    }

    @Test
    public void missingAndCorruptFilesReadAsNullSoTheCallerRebuilds() throws Exception {
        File dir = folder.newFolder("empty");
        assertNull(new FileLiveOutlineProjectionStore(dir).load());

        FileLiveOutlineProjectionStore store = new FileLiveOutlineProjectionStore(dir);
        store.save(sample());
        // Corrupt the persisted file (unknown schema) → null → rebuild, never an exception.
        File current = new File(new File(new File(new File(dir, "knowledge"), "projections"),
                "live-outline"), "current.properties");
        java.nio.file.Files.write(current.toPath(), "schemaVersion=99\ngarbage".getBytes("UTF-8"));
        assertNull(new FileLiveOutlineProjectionStore(dir).load());
    }

    @Test
    public void clusterAndCorpusIdentitiesAreDeterministicAndOrderIndependent() {
        String a = LiveTopicProjection.deterministicClusterId(Arrays.asList("p2", "p1"));
        String b = LiveTopicProjection.deterministicClusterId(Arrays.asList("p1", "p2"));
        assertEquals("same members (any order) → same cluster id", a, b);
        assertNotEquals(a, LiveTopicProjection.deterministicClusterId(Arrays.asList("p1", "p3")));

        assertEquals(LiveOutlineProjection.corpusFingerprintOf(Arrays.asList("x", "y")),
                LiveOutlineProjection.corpusFingerprintOf(Arrays.asList("y", "x")));
    }

    @Test
    public void anEmptyProjectionIsValidAndPersistable() throws Exception {
        File dir = folder.newFolder("noknowledge");
        LiveOutlineProjection empty = LiveOutlineProjection.empty(1L, "fpA", 99L);
        new FileLiveOutlineProjectionStore(dir).save(empty);
        LiveOutlineProjection back = new FileLiveOutlineProjectionStore(dir).load();
        assertEquals(0, back.getTopics().size());
        assertEquals(0, back.getSections().size());
        assertEquals(1L, back.getProjectionRevision());
    }
}
