package com.aresstack.askai.research.knowledge.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * The LIVE OUTLINE of the currently active knowledge corpus: a REBUILDABLE projection (passages + persisted
 * vectors → topic clusters → sections) that may change with every accepted source. It is NOT an approved
 * outline and NOT workflow state — there is no ACCEPTED/REJECTED lifecycle, no approval gate, and losing the
 * persisted copy only costs a rebuild, never data. A later slice freezes the then-current projection into a
 * committed {@code OutlineRevision} immediately before drafting; until then this stays deliberately mobile.
 */
public final class LiveOutlineProjection {

    /** The empty projection (no passages yet) — valid, renderable, revision-bearing. */
    public static LiveOutlineProjection empty(long projectionRevision, String embeddingFingerprint,
                                              long generatedAtMillis) {
        return new LiveOutlineProjection(projectionRevision, corpusFingerprintOf(
                Collections.<String>emptyList()), embeddingFingerprint, generatedAtMillis,
                Collections.<LiveTopicProjection>emptyList(), Collections.<LiveOutlineSection>emptyList());
    }

    private final long projectionRevision;
    /** Deterministic identity of the INPUT corpus (sorted included passage ids) this projection derives from. */
    private final String corpusFingerprint;
    private final String embeddingFingerprint;
    private final long generatedAtMillis;
    private final List<LiveTopicProjection> topics;
    private final List<LiveOutlineSection> sections;

    public LiveOutlineProjection(long projectionRevision, String corpusFingerprint,
                                 String embeddingFingerprint, long generatedAtMillis,
                                 List<LiveTopicProjection> topics, List<LiveOutlineSection> sections) {
        this.projectionRevision = projectionRevision;
        this.corpusFingerprint = corpusFingerprint == null ? "" : corpusFingerprint;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
        this.generatedAtMillis = generatedAtMillis;
        this.topics = Collections.unmodifiableList(new ArrayList<LiveTopicProjection>(
                topics == null ? Collections.<LiveTopicProjection>emptyList() : topics));
        this.sections = Collections.unmodifiableList(new ArrayList<LiveOutlineSection>(
                sections == null ? Collections.<LiveOutlineSection>emptyList() : sections));
    }

    /** Deterministic corpus identity: a hash over the SORTED passage ids that entered the projection. */
    public static String corpusFingerprintOf(List<String> includedPassageIds) {
        StringBuilder sb = new StringBuilder();
        for (String id : new TreeSet<String>(includedPassageIds == null
                ? Collections.<String>emptyList() : includedPassageIds)) {
            sb.append(id).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    public long getProjectionRevision() {
        return projectionRevision;
    }

    public String getCorpusFingerprint() {
        return corpusFingerprint;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public long getGeneratedAtMillis() {
        return generatedAtMillis;
    }

    public List<LiveTopicProjection> getTopics() {
        return topics;
    }

    public List<LiveOutlineSection> getSections() {
        return sections;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
