package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.research.store.MetadataLoadResult;
import com.aresstack.askai.research.store.ResearchProjectContext;
import com.aresstack.askai.research.store.ResearchProjectMetadata;

import java.io.IOException;

/**
 * FAIL-CLOSED commit of a confirmed research scope: metadata, concept and outline are written in
 * order and EVERY write result is checked. Only {@link ScopeCommitResult#isSuccess()} may trigger
 * the state-machine auto-advance; any failure leaves the state machine untouched and the scoping
 * dialog repeatable. This is not yet an atomic multi-file transaction — but a detected failure
 * never progresses the workflow.
 */
public final class ResearchScopeCommitService {

    public enum Status { SUCCESS, METADATA_FAILED, CONCEPT_FAILED, OUTLINE_FAILED, REVISION_CONFLICT }

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

        // 2. Concept — every ArtifactWriteResult is judged, never ignored. C5: scoping writes NO outline
        // anymore; the "outline" slot is the LIVE projection of the growing knowledge corpus, derived (and
        // continuously rebuilt) from accepted sources — never a pre-research document structure.
        ScopeCommitResult concept = writeArtifact("concept", scope.getConceptMarkdown(),
                Status.CONCEPT_FAILED);
        if (concept != null) {
            return concept;
        }
        return new ScopeCommitResult(Status.SUCCESS, "");
    }

    /** @return null on success, otherwise the typed failure (conflict vs. write error). */
    private ScopeCommitResult writeArtifact(String artifactId, String markdown,
                                            Status failureStatus) {
        ArtifactContent current = context.getArtifactStore().read(artifactId);
        ArtifactWriteResult result = context.getArtifactStore()
                .replace(artifactId, current.getRevision(), markdown);
        if (result.isSuccess()) {
            return null;
        }
        return classifyFailure(artifactId, result, failureStatus);
    }

    /**
     * A rejected replace that still carries a CURRENT revision (>= 0) is the optimistic-locking
     * conflict shape; a plain write error carries revision -1.
     */
    static ScopeCommitResult classifyFailure(String artifactId, ArtifactWriteResult result,
                                             Status failureStatus) {
        if (result.getRevision() >= 0) {
            return new ScopeCommitResult(Status.REVISION_CONFLICT,
                    artifactId + " changed concurrently (revision conflict at revision "
                            + result.getRevision() + ")");
        }
        return new ScopeCommitResult(failureStatus, artifactId + ": " + result.getReason());
    }
}
