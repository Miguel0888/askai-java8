package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.loop.ResearchRunWire;
import com.aresstack.askai.research.runtime.loop.ScopingProjectionSuggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a scoping output into the display-only projection wire line for the UI (RA-P6 §1/§10): exploration map
 * + search suggestions + advisory advice. Phase-isolated like {@link ScopingBriefSource}: only a
 * {@link ScopingAssistantOutput} projects; any other phase's output yields {@code null}, so no non-scoping turn
 * ever produces a scoping workspace projection. The research brief is deliberately NOT carried here — it has
 * its own single persistence route — keeping exactly one path per concern.
 */
public final class ScopingProjectionEncoder {

    private ScopingProjectionEncoder() {
    }

    /** The projection wire line for this output, or {@code null} when the phase produces no scoping projection. */
    public static String wireLineFor(String phaseId, PhaseAssistantOutput output) {
        if (!(output instanceof ScopingAssistantOutput)) {
            return null;
        }
        ScopingAssistantOutput scoping = (ScopingAssistantOutput) output;
        List<ScopingProjectionSuggestion> suggestions = new ArrayList<ScopingProjectionSuggestion>();
        for (SearchSuggestion suggestion : scoping.getSearchSuggestions()) {
            suggestions.add(new ScopingProjectionSuggestion(
                    suggestion.getQuery(), suggestion.getPurpose(), suggestion.getPriority()));
        }
        return ResearchRunWire.scopingProjection(
                phaseId,
                scoping.getExplorationMapMermaid(),
                suggestions,
                scoping.getAdvice().getRecommendation().name(),
                scoping.getAdvice().getReason());
    }
}
