package com.aresstack.askai.research.knowledge.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One section of the {@link LiveOutlineProjection} — a REBUILDABLE grouping of topic clusters, replaced
 * wholesale on every projection rebuild. No approval lifecycle; the {@code projectionSectionId} is stable
 * only as far as its underlying cluster identity is (deterministic per corpus, not a committed id). A later
 * FREEZE turns the then-current projection into a committed {@code OutlineRevision} with stable section ids —
 * that is deliberately NOT this type.
 */
public final class LiveOutlineSection {

    private final String projectionSectionId;
    private final String title;
    private final String parentProjectionSectionId;
    private final List<String> topicClusterIds;
    private final List<String> passageIds;
    private final List<String> uncoveredQuestions;

    public LiveOutlineSection(String projectionSectionId, String title, String parentProjectionSectionId,
                              List<String> topicClusterIds, List<String> passageIds,
                              List<String> uncoveredQuestions) {
        this.projectionSectionId = projectionSectionId == null ? "" : projectionSectionId;
        this.title = title == null ? "" : title;
        this.parentProjectionSectionId =
                parentProjectionSectionId == null ? "" : parentProjectionSectionId;
        this.topicClusterIds = copy(topicClusterIds);
        this.passageIds = copy(passageIds);
        this.uncoveredQuestions = copy(uncoveredQuestions);
    }

    public String getProjectionSectionId() {
        return projectionSectionId;
    }

    public String getTitle() {
        return title;
    }

    /** The parent section's projection id, or "" for a root section. */
    public String getParentProjectionSectionId() {
        return parentProjectionSectionId;
    }

    public List<String> getTopicClusterIds() {
        return topicClusterIds;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }

    /** Confirmed research questions this section does NOT yet cover (the visible gaps). */
    public List<String> getUncoveredQuestions() {
        return uncoveredQuestions;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }
}
