package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.SearchResultExtractionSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfile;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileMatch;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileQuery;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies and records validated layout profiles by STRUCTURE, never by container id (A4e). Reuse is
 * only permitted when the engine family and the fingerprint/structure/ancestry signatures are
 * compatible AND a current container re-resolves to that structure AND the re-derived selection
 * re-validates against the current artifact. On any deviation the profile is not applied and the AI
 * path stays open. A reused profile can never resurrect a raw old container id — it re-derives the id
 * from the CURRENT document.
 */
public final class SearchPageLayoutProfileService {

    private final SearchPageLayoutDecisionValidator validator;

    public SearchPageLayoutProfileService(SearchResultExtractionSettings extraction) {
        this.validator = new SearchPageLayoutDecisionValidator(extraction);
    }

    /**
     * Try to resolve the current low-confidence layout from a stored profile without calling the AI.
     * Returns the re-derived, re-validated decision, or null when no compatible profile re-resolves.
     * A used profile is re-saved with a bumped validation stamp.
     */
    public ValidatedSearchPageLayoutDecision resolveFromProfiles(RenderedPageDocument document,
                                                                 SearchPageAnalysisArtifact artifact,
                                                                 SearchPageLayoutProfileStore store,
                                                                 long nowEpochMillis) {
        String fingerprint = artifact.documentFingerprint;
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            SearchPageLayoutProfileQuery query = new SearchPageLayoutProfileQuery(
                    artifact.engineFamily, fingerprint, artifact.settingsDigest,
                    candidate.structureSignature, ancestrySignature(document, candidate.containerId));
            SearchPageLayoutProfileMatch match = store.find(query);
            if (!match.matched) {
                continue;
            }
            SearchPageLayoutResolutionDecision decision = new SearchPageLayoutResolutionDecision(
                    artifact.snapshotId, Collections.singletonList(candidate.containerId),
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    profileConfidence(match.profile), "reused validated layout profile");
            SearchPageLayoutValidationResult validation = validator.validate(decision, artifact);
            if (!validation.valid) {
                continue; // profile no longer re-validates against the current structure — drop it
            }
            store.saveValidated(match.profile.revalidated(nowEpochMillis));
            return validator.toValidatedDecision(decision, artifact);
        }
        return null;
    }

    /**
     * Build a STRUCTURE-only profile from a validated decision on the current document — the region
     * and block structure signatures and the ancestry, never the snapshot-local container ids.
     */
    public SearchPageLayoutProfile buildProfile(RenderedPageDocument document,
                                                SearchPageAnalysisArtifact artifact,
                                                ValidatedSearchPageLayoutDecision decision,
                                                long nowEpochMillis) {
        RenderedContainerDescriptor region =
                document.container(decision.primaryOrganicContainerId);
        String regionSignature = region == null || region.structureSignature == null
                ? "" : region.structureSignature.value;
        List<String> blockSignatures = new ArrayList<String>();
        if (region != null) {
            Set<String> distinct = new LinkedHashSet<String>();
            for (String childId : region.childContainerIds) {
                RenderedContainerDescriptor child = document.container(childId);
                if (child != null && child.structureSignature != null
                        && !child.structureSignature.value.isEmpty()) {
                    distinct.add(child.structureSignature.value);
                }
            }
            blockSignatures.addAll(distinct);
        }
        return new SearchPageLayoutProfile(artifact.engineFamily, artifact.documentFingerprint,
                FileSearchPageLayoutProfileStore.STRUCTURE_SIGNATURE_VERSION, regionSignature,
                blockSignatures, ancestrySignature(document, decision.primaryOrganicContainerId),
                artifact.settingsDigest, nowEpochMillis, nowEpochMillis, 1);
    }

    /** A stable tag-name ancestry from the container up to the root, e.g. {@code body>div>main}. */
    private String ancestrySignature(RenderedPageDocument document, String containerId) {
        List<String> chain = new ArrayList<String>();
        String current = containerId;
        Set<String> visited = new LinkedHashSet<String>();
        while (current != null && !current.isEmpty() && visited.add(current)) {
            RenderedContainerDescriptor descriptor = document.container(current);
            if (descriptor == null) {
                break;
            }
            chain.add(descriptor.tagName.isEmpty() ? "?" : descriptor.tagName);
            current = descriptor.parentContainerId;
        }
        Collections.reverse(chain);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append('>');
            }
            sb.append(chain.get(i));
        }
        return sb.toString();
    }

    private static double profileConfidence(SearchPageLayoutProfile profile) {
        // A revalidated structural profile is a strong-but-not-certain prior.
        return 0.75;
    }
}
