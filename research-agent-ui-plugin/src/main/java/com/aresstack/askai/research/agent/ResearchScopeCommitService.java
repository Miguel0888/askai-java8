package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.store.MetadataLoadResult;
import com.aresstack.askai.research.store.ResearchProjectContext;
import com.aresstack.askai.research.store.ResearchProjectMetadata;

import java.io.IOException;

/**
 * FAIL-CLOSED commit of a confirmed research scope: the typed metadata is written and the write result is
 * checked. Only {@link ScopeCommitResult#isSuccess()} may trigger the state-machine auto-advance; any failure
 * leaves the state machine untouched and the scoping dialog repeatable. Issue #32: the commit writes NO
 * concept (and since C5 no outline) Markdown artifact anymore — the ResearchBrief is the canonical scoping
 * truth and the metadata is the typed contract; there is no second document beside them.
 */
public final class ResearchScopeCommitService {

    public enum Status { SUCCESS, METADATA_FAILED, REVISION_CONFLICT }

    /** Typed outcome with the concrete failure detail for the (localized) user message. */
    public static final class ScopeCommitResult {
        private final Status status;
        private final String detail;

        ScopeCommitResult(Status status, String detail) {
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public Status getStatus() {
            return status;
        }

        public String getDetail() {
            return detail;
        }
    }

    private final ResearchProjectContext context;

    public ResearchScopeCommitService(ResearchProjectContext context) {
        this.context = context;
    }

    public ScopeCommitResult commit(ConfirmedResearchScope scope) {
        // 1. The typed research assignment (the contract; Markdown below is only presentation).
        MetadataLoadResult previous = context.getMetadataStore().load(context.getProjectId());
        if (!previous.isUsableForStart()) {
            return new ScopeCommitResult(Status.METADATA_FAILED,
                    "existing metadata unusable: " + previous.getReason());
        }
        long revision = previous.getStatus() == MetadataLoadResult.Status.LOADED
                ? previous.getMetadata().getRevision() + 1L : 1L;
        try {
            context.getMetadataStore().save(new ResearchProjectMetadata(
                    ResearchProjectMetadata.SCHEMA_VERSION, context.getProjectId(),
                    scope.getResearchQuestion(), scope.getConfirmedFocusAreas(), revision));
        } catch (IOException persistFailed) {
            return new ScopeCommitResult(Status.METADATA_FAILED, persistFailed.getMessage());
        }

        // Issue #32: no concept artifact write anymore — the metadata above IS the commit. C5 already
        // removed the outline write (the "outline" slot is the derived projection of the corpus).
        return new ScopeCommitResult(Status.SUCCESS, "");
    }
}
