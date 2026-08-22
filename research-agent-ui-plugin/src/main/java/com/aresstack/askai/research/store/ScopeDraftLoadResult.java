package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;

/**
 * Typed outcome of loading the scope draft. Only {@link Status#MISSING} is a legitimate "scoping has not
 * started yet"; every other non-LOADED status marks a DAMAGED draft. Such a draft must never be silently
 * replaced by an empty one — that would quietly throw away decisions the user already made.
 */
public final class ScopeDraftLoadResult {

    public enum Status { MISSING, LOADED, CORRUPT, UNSUPPORTED_SCHEMA }

    private final Status status;
    private final ResearchScopeDraft draft; // non-null only for LOADED
    private final String reason;

    private ScopeDraftLoadResult(Status status, ResearchScopeDraft draft, String reason) {
        this.status = status;
        this.draft = draft;
        this.reason = reason == null ? "" : reason;
    }

    static ScopeDraftLoadResult missing() {
        return new ScopeDraftLoadResult(Status.MISSING, null, "");
    }

    static ScopeDraftLoadResult loaded(ResearchScopeDraft draft) {
        return new ScopeDraftLoadResult(Status.LOADED, draft, "");
    }

    static ScopeDraftLoadResult failed(Status status, String reason) {
        return new ScopeDraftLoadResult(status, null, reason);
    }

    public Status getStatus() {
        return status;
    }

    /** The draft for {@link Status#LOADED}; null otherwise. */
    public ResearchScopeDraft getDraft() {
        return draft;
    }

    public String getReason() {
        return reason;
    }

    /** MISSING = start a fresh draft; LOADED = continue this one. Anything else must block, not overwrite. */
    public boolean isUsableForScoping() {
        return status == Status.MISSING || status == Status.LOADED;
    }

    /** The draft to work with: the loaded one, or an empty draft for MISSING. */
    public ResearchScopeDraft draftOrEmpty() {
        return draft == null ? ResearchScopeDraft.empty() : draft;
    }
}
