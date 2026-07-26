package com.aresstack.askai.research.domain;

/** An immutable problem/open question, identified by a stable {@code id} and optionally tied to a section. */
public final class ResearchProblem {

    public enum Kind {
        OPEN_QUESTION,
        BLOCKED_SOURCE,
        CONTRADICTION,
        TECHNICAL_ERROR
    }

    private final String id;
    private final Kind kind;
    private final String message;
    private final String sectionId;

    public ResearchProblem(String id, Kind kind, String message, String sectionId) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("problem id must not be empty");
        }
        this.id = id;
        this.kind = kind == null ? Kind.OPEN_QUESTION : kind;
        this.message = message == null ? "" : message;
        this.sectionId = sectionId == null ? "" : sectionId;
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public String getMessage() {
        return message;
    }

    public String getSectionId() {
        return sectionId;
    }
}
