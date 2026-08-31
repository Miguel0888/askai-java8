package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one {@link ScopeSweepService.SweepPlan} from ONE immutable {@link ResearchScopeDraft}
 * snapshot — the SINGLE assembly point GPT demanded: revision, generation request, mission
 * references and anchors all derive from the SAME object, so a mixed-snapshot plan (request from
 * draft R, vectors from R-1) cannot be assembled here by construction. The anchor vectors come in
 * separately because reconciling them costs I/O (index + embedding) — the plan's own
 * anchor-consistency validation still verifies they describe THIS draft's fence.
 */
public final class ScopeSweepPlanAssembler {

    private ScopeSweepPlanAssembler() {
    }

    /** The generation request, derived exclusively from the draft snapshot + configuration. */
    public static ProbeGenerationRequest requestOf(ResearchScopeDraft draft,
                                                   ScopeSweepConfiguration configuration) {
        return requestOf(draft,
                java.util.Collections.<com.aresstack.askai.research.domain.scope.ScopeAnchor>
                        emptyList(), configuration);
    }

    /**
     * Zielbild slice 1: the fence is a PROJECTION of the two artifacts — the draft's own anchors
     * (EXCLUDED → OUT) PLUS the effective mindmap's IN posts, assembled here so request and
     * vectors can never describe different fences. The concept anchors' texts join the known
     * labels, so the broad generator does not re-paraphrase existing cards.
     */
    public static ProbeGenerationRequest requestOf(
            ResearchScopeDraft draft,
            List<com.aresstack.askai.research.domain.scope.ScopeAnchor> conceptAnchors,
            ScopeSweepConfiguration configuration) {
        List<String> knownLabels = facetLabels(draft);
        for (com.aresstack.askai.research.domain.scope.ScopeAnchor anchor : conceptAnchors) {
            if (!anchor.getSemanticText().isEmpty()) {
                knownLabels.add(anchor.getSemanticText());
            }
        }
        return new ProbeGenerationRequest(draft.getMission(), draft.getDomains(),
                draft.getContexts(), knownLabels, combinedAnchors(draft, conceptAnchors),
                configuration.targetBroadProbes);
    }

    /** Draft anchors first (they own the facet ids), concept posts appended, deduped by id. */
    public static List<com.aresstack.askai.research.domain.scope.ScopeAnchor> combinedAnchors(
            ResearchScopeDraft draft,
            List<com.aresstack.askai.research.domain.scope.ScopeAnchor> conceptAnchors) {
        List<com.aresstack.askai.research.domain.scope.ScopeAnchor> combined =
                new ArrayList<com.aresstack.askai.research.domain.scope.ScopeAnchor>(
                        draft.getAnchors());
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (com.aresstack.askai.research.domain.scope.ScopeAnchor anchor : combined) {
            ids.add(anchor.getAnchorId());
        }
        for (com.aresstack.askai.research.domain.scope.ScopeAnchor anchor : conceptAnchors) {
            if (ids.add(anchor.getAnchorId())) {
                combined.add(anchor);
            }
        }
        return combined;
    }

    /**
     * The mission reference texts: the mission itself plus the coarse frame (domains, contexts) —
     * the same reference set the live gates calibrated against. ALL from the one snapshot.
     */
    public static List<String> missionReferenceTexts(ResearchScopeDraft draft) {
        List<String> texts = new ArrayList<String>();
        if (!draft.getMission().trim().isEmpty()) {
            texts.add(draft.getMission());
        }
        texts.addAll(draft.getDomains());
        texts.addAll(draft.getContexts());
        return texts;
    }

    public static ScopeSweepService.SweepPlan planOf(ResearchScopeDraft draft,
                                                     String embeddingFingerprint,
                                                     List<AnchorVector> anchorVectors,
                                                     ScopeSweepConfiguration configuration) {
        return planOf(draft,
                java.util.Collections.<com.aresstack.askai.research.domain.scope.ScopeAnchor>
                        emptyList(),
                embeddingFingerprint, anchorVectors, configuration);
    }

    /** As above, with the effective mindmap's IN posts joining the fence (Zielbild slice 1). */
    public static ScopeSweepService.SweepPlan planOf(
            ResearchScopeDraft draft,
            List<com.aresstack.askai.research.domain.scope.ScopeAnchor> conceptAnchors,
            String embeddingFingerprint,
            List<AnchorVector> anchorVectors,
            ScopeSweepConfiguration configuration) {
        return new ScopeSweepService.SweepPlan(draft.getRevision(), embeddingFingerprint,
                requestOf(draft, conceptAnchors, configuration), anchorVectors,
                missionReferenceTexts(draft),
                configuration.fenceThresholds, configuration.calibrationParameters,
                configuration.boundaryMargin, configuration.sweepNoveltyGap,
                configuration.selectorParameters);
    }

    /**
     * ALL facet labels, including PROVISIONAL ones: an open hypothesis must be KNOWN to the broad
     * generator (so it does not re-paraphrase it) even though it is never a calibration anchor.
     */
    private static List<String> facetLabels(ResearchScopeDraft draft) {
        List<String> labels = new ArrayList<String>();
        for (ScopeFacet facet : draft.getFacets()) {
            if (!facet.getLabel().trim().isEmpty()) {
                labels.add(facet.getLabel());
            }
        }
        return labels;
    }
}
