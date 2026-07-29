package com.aresstack.askai.research.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TYPED research assignment of one project: the confirmed research question and focus areas.
 * This is the metadata CONTRACT — it is never parsed back out of {@code concept.md} (Markdown is
 * presentation, not storage). Persisted as {@code project.properties} in the project root.
 */
public final class ResearchProjectMetadata {

    public static final int SCHEMA_VERSION = 1;

    private final int schemaVersion;
    private final String projectId;
    private final String researchQuestion;
    private final List<String> confirmedFocusAreas;
    private final long revision;

    public ResearchProjectMetadata(int schemaVersion, String projectId, String researchQuestion,
                                   List<String> confirmedFocusAreas, long revision) {
        this.schemaVersion = schemaVersion;
        this.projectId = projectId == null ? "" : projectId;
        this.researchQuestion = researchQuestion == null ? "" : researchQuestion;
        this.confirmedFocusAreas = Collections.unmodifiableList(confirmedFocusAreas == null
                ? new ArrayList<String>() : new ArrayList<String>(confirmedFocusAreas));
        this.revision = revision;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getResearchQuestion() {
        return researchQuestion;
    }

    public List<String> getConfirmedFocusAreas() {
        return confirmedFocusAreas;
    }

    public long getRevision() {
        return revision;
    }

    public boolean hasResearchQuestion() {
        return !researchQuestion.trim().isEmpty();
    }
}
