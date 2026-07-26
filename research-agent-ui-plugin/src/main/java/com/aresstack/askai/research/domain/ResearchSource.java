package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/** An immutable captured source, identified by a stable {@code id} and linked to sections by id. */
public final class ResearchSource {

    private final String id;
    private final String title;
    private final String origin;
    private final long capturedAt;
    private final String sourceType;
    private final double trust;
    private final List<String> linkedSectionIds;
    private final String status;

    public ResearchSource(String id, String title, String origin, long capturedAt, String sourceType,
                          double trust, List<String> linkedSectionIds, String status) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("source id must not be empty");
        }
        this.id = id;
        this.title = title == null ? "" : title;
        this.origin = origin == null ? "" : origin;
        this.capturedAt = capturedAt;
        this.sourceType = sourceType == null ? "" : sourceType;
        this.trust = trust;
        this.linkedSectionIds = linkedSectionIds == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(linkedSectionIds));
        this.status = status == null ? "" : status;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getOrigin() {
        return origin;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public String getSourceType() {
        return sourceType;
    }

    public double getTrust() {
        return trust;
    }

    public List<String> getLinkedSectionIds() {
        return linkedSectionIds;
    }

    public String getStatus() {
        return status;
    }
}
