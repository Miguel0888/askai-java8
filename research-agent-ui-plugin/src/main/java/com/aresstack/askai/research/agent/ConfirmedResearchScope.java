package com.aresstack.askai.research.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The user-confirmed research scope plus the documents derived from it, ready to be committed. */
public final class ConfirmedResearchScope {

    private final String researchQuestion;
    private final List<String> confirmedFocusAreas;
    private final String conceptMarkdown;
    private final String outlineMarkdown;

    public ConfirmedResearchScope(String researchQuestion, List<String> confirmedFocusAreas,
                                  String conceptMarkdown, String outlineMarkdown) {
        this.researchQuestion = researchQuestion == null ? "" : researchQuestion;
        this.confirmedFocusAreas = Collections.unmodifiableList(confirmedFocusAreas == null
                ? new ArrayList<String>() : new ArrayList<String>(confirmedFocusAreas));
        this.conceptMarkdown = conceptMarkdown == null ? "" : conceptMarkdown;
        this.outlineMarkdown = outlineMarkdown == null ? "" : outlineMarkdown;
    }

    public String getResearchQuestion() {
        return researchQuestion;
    }

    public List<String> getConfirmedFocusAreas() {
        return confirmedFocusAreas;
    }

    public String getConceptMarkdown() {
        return conceptMarkdown;
    }

    public String getOutlineMarkdown() {
        return outlineMarkdown;
    }
}
