package com.aresstack.askai.research.knowledge.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * One topic CLUSTER of the live knowledge projection — a REBUILDABLE, neutral view of the currently active
 * corpus. Deliberately NOT a {@code TopicProposal}: it has no ACCEPTED/REJECTED lifecycle and is replaced
 * wholesale on every rebuild. The {@link #getClusterId()} is DETERMINISTIC (derived from the sorted member
 * passage ids), so the same corpus clusters to the same ids on every rebuild — an unchanged topic keeps its
 * identity across projections without any mutable state.
 */
public final class LiveTopicProjection {

    private final String clusterId;
    private final List<String> memberPassageIds;
    private final List<String> representativePassageIds;
    private final String title;
    private final double confidence;

    public LiveTopicProjection(List<String> memberPassageIds, List<String> representativePassageIds,
                               String title, double confidence) {
        this(deterministicClusterId(memberPassageIds), memberPassageIds, representativePassageIds,
                title, confidence);
    }

    public LiveTopicProjection(String clusterId, List<String> memberPassageIds,
                               List<String> representativePassageIds, String title, double confidence) {
        this.clusterId = clusterId == null ? "" : clusterId;
        this.memberPassageIds = copy(memberPassageIds);
        this.representativePassageIds = copy(representativePassageIds);
        this.title = title == null ? "" : title;
        this.confidence = confidence;
    }

    /** Stable identity of a cluster = a hash of its SORTED member passage ids (order-independent). */
    public static String deterministicClusterId(List<String> memberPassageIds) {
        StringBuilder sb = new StringBuilder();
        for (String id : new TreeSet<String>(memberPassageIds == null
                ? Collections.<String>emptyList() : memberPassageIds)) {
            sb.append(id).append('\n');
        }
        return "topic-" + sha256Hex(sb.toString()).substring(0, 16);
    }

    public String getClusterId() {
        return clusterId;
    }

    public List<String> getMemberPassageIds() {
        return memberPassageIds;
    }

    public List<String> getRepresentativePassageIds() {
        return representativePassageIds;
    }

    public String getTitle() {
        return title;
    }

    public double getConfidence() {
        return confidence;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
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
