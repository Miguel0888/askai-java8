package com.aresstack.askai.research.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An immutable finding linked to its supporting sources and the sections it belongs to (all by id). */
public final class ResearchFinding {

    private final String id;
    private final String statement;
    private final List<String> linkedSourceIds;
    private final List<String> linkedSectionIds;
    private final double confidence;
    private final boolean contradiction;
    private final String status;

    public ResearchFinding(String id, String statement, List<String> linkedSourceIds,
                           List<String> linkedSectionIds, double confidence, boolean contradiction,
                           String status) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("finding id must not be empty");
        }
        this.id = id;
        this.statement = statement == null ? "" : statement;
        this.linkedSourceIds = copy(linkedSourceIds);
        this.linkedSectionIds = copy(linkedSectionIds);
        this.confidence = confidence;
        this.contradiction = contradiction;
        this.status = status == null ? "" : status;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getId() {
        return id;
    }

    public String getStatement() {
        return statement;
    }

    public List<String> getLinkedSourceIds() {
        return linkedSourceIds;
    }

    public List<String> getLinkedSectionIds() {
        return linkedSectionIds;
    }

    public double getConfidence() {
        return confidence;
    }

    public boolean isContradiction() {
        return contradiction;
    }

    public String getStatus() {
        return status;
    }
}
