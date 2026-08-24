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
        return new ProbeGenerationRequest(draft.getMission(), draft.getDomains(),
                draft.getContexts(), facetLabels(draft), draft.getAnchors(),
                configuration.targetBroadProbes);
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
        return new ScopeSweepService.SweepPlan(draft.getRevision(), embeddingFingerprint,
                requestOf(draft, configuration), anchorVectors, missionReferenceTexts(draft),
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
