package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.SearchResultExtractionSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationViolation;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationViolation.Kind;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, purely-structural validation of a raw model layout decision against its artifact (A4c). The
 * hard invariant is absolute: a container id the mechanics did not offer is NEVER accepted — it is a
 * validation failure that may trigger a bounded repair retry, never a silently applied guess. The
 * model's free-text explanation is ignored entirely. On success it produces the
 * {@link ValidatedSearchPageLayoutDecision} the extractor may apply.
 */
final class SearchPageLayoutDecisionValidator {

    private final SearchResultExtractionSettings extraction;

    SearchPageLayoutDecisionValidator(SearchResultExtractionSettings extraction) {
        this.extraction = extraction;
    }

    SearchPageLayoutValidationResult validate(SearchPageLayoutResolutionDecision decision,
                                              SearchPageAnalysisArtifact artifact) {
        List<SearchPageLayoutValidationViolation> violations =
                new ArrayList<SearchPageLayoutValidationViolation>();

        if (!artifact.snapshotId.equals(decision.snapshotId)) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.UNKNOWN_SNAPSHOT,
                    "decision snapshot '" + decision.snapshotId + "' does not match artifact snapshot '"
                            + artifact.snapshotId + "'"));
        }
        if (!artifact.analysisId.equals(decision.analysisId)) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.ANALYSIS_MISMATCH,
                    "decision analysis '" + decision.analysisId + "' does not match artifact analysis '"
                            + artifact.analysisId + "'"));
        }

        Map<String, String> parentById = parentIndex(artifact);

        checkKnown(decision.organicResultContainerIds, "organicResultContainerIds", artifact,
                violations);
        checkKnown(decision.resultBlockContainerIds, "resultBlockContainerIds", artifact, violations);
        checkKnown(decision.excludedContainerIds, "excludedContainerIds", artifact, violations);

        checkDuplicates(decision.organicResultContainerIds, "organicResultContainerIds", violations);
        checkDuplicates(decision.resultBlockContainerIds, "resultBlockContainerIds", violations);
        checkDuplicates(decision.excludedContainerIds, "excludedContainerIds", violations);

        Set<String> organic = new HashSet<String>(decision.organicResultContainerIds);
        for (String id : decision.excludedContainerIds) {
            if (organic.contains(id)) {
                violations.add(new SearchPageLayoutValidationViolation(
                        Kind.CONTRADICTORY_CLASSIFICATION,
                        "container '" + id + "' is both organic and excluded"));
            }
        }

        if (decision.organicResultContainerIds.isEmpty()) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.NO_ORGANIC_CONTAINER,
                    "no organic result container was named"));
        }

        if (decision.confidence < 0.0 || decision.confidence > 1.0
                || Double.isNaN(decision.confidence)) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.INVALID_CONFIDENCE,
                    "confidence " + decision.confidence + " is outside 0..1"));
        }

        int maxOrganic = Math.max(1, artifact.containerCandidates.size());
        if (decision.organicResultContainerIds.size() > maxOrganic) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.LIMIT_EXCEEDED,
                    "organic container count " + decision.organicResultContainerIds.size()
                            + " exceeds the candidate count " + maxOrganic));
        }
        if (decision.resultBlockContainerIds.size() > extraction.maximumExtractedCandidates) {
            violations.add(new SearchPageLayoutValidationViolation(Kind.LIMIT_EXCEEDED,
                    "result block count " + decision.resultBlockContainerIds.size()
                            + " exceeds the configured maximum "
                            + extraction.maximumExtractedCandidates));
        }

        // Root/full-page selection: a top-level container (no known parent) is never a result region.
        for (String id : decision.organicResultContainerIds) {
            if (parentById.containsKey(id)) {
                String parent = parentById.get(id);
                if (parent == null || parent.isEmpty()) {
                    violations.add(new SearchPageLayoutValidationViolation(Kind.FULL_PAGE_SELECTION,
                            "organic container '" + id + "' is a root/full-page container"));
                }
            }
        }

        // Every provided result block must sit directly inside a chosen organic region.
        for (String block : decision.resultBlockContainerIds) {
            if (!parentById.containsKey(block)) {
                continue; // unknown-id is already reported above; nothing more to prove here
            }
            String parent = parentById.get(block);
            if (!organic.contains(parent)) {
                violations.add(new SearchPageLayoutValidationViolation(Kind.BLOCK_OUTSIDE_REGION,
                        "result block '" + block + "' is not inside a chosen organic region"));
            }
        }

        return new SearchPageLayoutValidationResult(violations);
    }

    /**
     * Builds the validated, safe-to-apply decision from an already-valid raw decision. The first
     * organic container becomes the primary region the extractor seeds its block detection with.
     */
    ValidatedSearchPageLayoutDecision toValidatedDecision(SearchPageLayoutResolutionDecision decision,
                                                          SearchPageAnalysisArtifact artifact) {
        String primary = decision.organicResultContainerIds.isEmpty()
                ? "" : decision.organicResultContainerIds.get(0);
        // Bind the trusted values from the ARTIFACT, not from the model — the model only chose ids.
        return new ValidatedSearchPageLayoutDecision(artifact.analysisId, artifact.snapshotId,
                artifact.snapshotGeneration, artifact.documentFingerprint, artifact.settingsDigest,
                primary, decision.organicResultContainerIds, decision.resultBlockContainerIds,
                decision.excludedContainerIds, decision.confidence);
    }

    private void checkKnown(List<String> ids, String field, SearchPageAnalysisArtifact artifact,
                            List<SearchPageLayoutValidationViolation> violations) {
        for (String id : ids) {
            if (!artifact.knowsContainer(id)) {
                violations.add(new SearchPageLayoutValidationViolation(Kind.UNKNOWN_CONTAINER_ID,
                        field + " references unknown container id '" + id + "'"));
            }
        }
    }

    private void checkDuplicates(List<String> ids, String field,
                                 List<SearchPageLayoutValidationViolation> violations) {
        Set<String> seen = new HashSet<String>();
        for (String id : ids) {
            if (!seen.add(id)) {
                violations.add(new SearchPageLayoutValidationViolation(Kind.DUPLICATE_ID,
                        field + " lists '" + id + "' more than once"));
            }
        }
    }

    private Map<String, String> parentIndex(SearchPageAnalysisArtifact artifact) {
        Map<String, String> parentById = new HashMap<String, String>();
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            parentById.put(candidate.containerId, candidate.parentContainerId);
        }
        return parentById;
    }
}
