package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjection;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import java.util.ArrayList;
import java.util.List;

/**
 * #42 — the ONE phase-neutral topic-discovery use case: read the active corpus, run the
 * EXISTING {@link LiveOutlineProjectionBuilder#discoverTopics} (the established HAC/cosine
 * math — deliberately no second clustering implementation, no concept-specific variant) and
 * persist the result as a fingerprint-pinned snapshot. Phase 1 consumes the snapshot for
 * concept ideas in the chat; the outline build consumes the SAME snapshot for phase 3 — when
 * the discovery improves later, both consumers profit automatically.
 *
 * <p>Discovery is PURE CPU over already-persisted vectors — it never calls the embedding
 * endpoint, so a background refresh cannot compete with the foreground GPU/LLM path by
 * construction (the resource guard of the issue).</p>
 */
public final class SharedTopicDiscovery {

    /** The corpus seam (reader + source filter bound by the session factory). */
    public interface CorpusSource {
        ActiveKnowledgeCorpusReader.Corpus read();
    }

    private final CorpusSource corpus;
    private final LiveOutlineProjectionBuilder builder;
    private final FileTopicSnapshotStore store;
    private final String embeddingFingerprint;

    public SharedTopicDiscovery(CorpusSource corpus, LiveOutlineProjectionBuilder builder,
                                FileTopicSnapshotStore store, String embeddingFingerprint) {
        this.corpus = corpus;
        this.builder = builder;
        this.store = store;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
    }

    /** The atomically pinned pair a combining consumer builds from — never two reads. */
    public static final class Pinned {
        public final ActiveKnowledgeCorpusReader.Corpus corpus;
        public final FileTopicSnapshotStore.TopicSnapshot snapshot;

        Pinned(ActiveKnowledgeCorpusReader.Corpus corpus,
               FileTopicSnapshotStore.TopicSnapshot snapshot) {
            this.corpus = corpus;
            this.snapshot = snapshot;
        }
    }

    /**
     * ONE deterministic discovery run over the CURRENT corpus: cluster (existing math),
     * fingerprint, persist, return. Topics only — never an outline, never a concept
     * mutation, never a phase change.
     */
    public FileTopicSnapshotStore.TopicSnapshot refresh(long nowMillis) {
        return refreshPinned(nowMillis).snapshot;
    }

    /**
     * The corpus read, the clustering AND the snapshot write happen under ONE lock, and the
     * corpus used is returned WITH the topics: a consumer that combines both (the outline
     * build: passages + topics) gets one coherent pair, and two concurrent refreshes can
     * never write an older corpus's snapshot over a newer one (reads outside the lock did
     * exactly that).
     */
    public synchronized Pinned refreshPinned(long nowMillis) {
        ActiveKnowledgeCorpusReader.Corpus current = corpus.read();
        List<LiveTopicProjection> topics =
                builder.discoverTopics(current.getPassages(), current.getVectors());
        FileTopicSnapshotStore.TopicSnapshot previous = store.load();
        FileTopicSnapshotStore.TopicSnapshot snapshot = new FileTopicSnapshotStore.TopicSnapshot(
                (previous == null ? 0L : previous.revision) + 1L,
                corpusFingerprintOf(current), embeddingFingerprint, nowMillis, topics);
        store.save(snapshot);
        return new Pinned(current, snapshot);
    }

    /** The persisted snapshot, or {@code null} when none exists yet. Pure read. */
    public FileTopicSnapshotStore.TopicSnapshot current() {
        return store.load();
    }

    /**
     * Whether the persisted snapshot still describes the CURRENT corpus + embedding world.
     * {@code true} also when no snapshot exists but the corpus has content. Pure read —
     * never triggers a rebuild.
     */
    public boolean isStale() {
        ActiveKnowledgeCorpusReader.Corpus current = corpus.read();
        FileTopicSnapshotStore.TopicSnapshot persisted = store.load();
        if (persisted == null) {
            return !current.getPassages().isEmpty();
        }
        return !corpusFingerprintOf(current).equals(persisted.corpusFingerprint)
                || !embeddingFingerprint.equals(persisted.embeddingFingerprint);
    }

    /** The SAME corpus-identity math the outline staleness uses — one fingerprint truth. */
    private static String corpusFingerprintOf(ActiveKnowledgeCorpusReader.Corpus corpus) {
        List<String> ids = new ArrayList<String>();
        for (Passage passage : corpus.getPassages()) {
            ids.add(passage.getPassageId());
        }
        return LiveOutlineProjection.corpusFingerprintOf(ids);
    }
}
