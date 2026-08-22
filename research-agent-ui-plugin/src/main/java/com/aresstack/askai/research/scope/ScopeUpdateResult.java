package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a scoping turn actually did to the scope — a typed answer instead of a silent merge, so a rejected
 * or empty update is visible rather than looking like success.
 */
public final class ScopeUpdateResult {

    public enum Status {
        /** The draft changed and the new revision is persisted. */
        APPLIED,
        /** The turn proposed nothing new; the draft (and its revision) is untouched. */
        UNCHANGED,
        /** Nothing was applied — the reason says why (unreadable draft, failed write). */
        REJECTED
    }

    private final Status status;
    private final ResearchScopeDraft draft;
    private final List<String> changes;
    private final String reason;

    private ScopeUpdateResult(Status status, ResearchScopeDraft draft, List<String> changes, String reason) {
        this.status = status;
        this.draft = draft;
        this.changes = changes == null || changes.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(changes));
        this.reason = reason == null ? "" : reason;
    }

    static ScopeUpdateResult applied(ResearchScopeDraft draft, List<String> changes) {
        return new ScopeUpdateResult(Status.APPLIED, draft, changes, "");
    }

    static ScopeUpdateResult unchanged(ResearchScopeDraft draft) {
        return new ScopeUpdateResult(Status.UNCHANGED, draft, null, "");
    }

    static ScopeUpdateResult rejected(ResearchScopeDraft draft, String reason) {
        return new ScopeUpdateResult(Status.REJECTED, draft, null, reason);
    }

    public Status getStatus() {
        return status;
    }

    /** The draft AFTER this update — for UNCHANGED/REJECTED the unchanged current one. */
    public ResearchScopeDraft getDraft() {
        return draft;
    }

    /** One line per applied change, for diagnostics and for showing the user what was recorded. */
    public List<String> getChanges() {
        return changes;
    }

    public String getReason() {
        return reason;
    }

    public boolean isApplied() {
        return status == Status.APPLIED;
    }
}
