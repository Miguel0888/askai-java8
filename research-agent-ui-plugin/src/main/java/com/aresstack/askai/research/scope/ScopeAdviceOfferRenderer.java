package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.CandidateOffer;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceRequest;
import com.aresstack.askai.research.domain.scope.ScopeAdviceSet;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeDriftGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ScopeAdviceSet} into the chooser's {@link ChoiceRequest}: candidate ids stay
 * VERBATIM (the decision must map back), the anchor RELATIONS become human-readable context in
 * the conversation's language — the chooser phrases a question for the user, so it needs the
 * posts' semantic TEXTS, not their ids. Drift guards render as explicitly non-selectable
 * reminders. Deliberately draft-coupled: the anchor texts come from the SAME draft snapshot the
 * sweep ran on.
 */
public final class ScopeAdviceOfferRenderer {

    private ScopeAdviceOfferRenderer() {
    }

    public static ChoiceRequest render(ScopeAdviceSet advice, ResearchScopeDraft draft) {
        Map<String, String> anchorTexts = new LinkedHashMap<String, String>();
        for (ScopeAnchor anchor : draft.getAnchors()) {
            anchorTexts.put(anchor.getAnchorId(), anchor.getSemanticText());
        }
        List<CandidateOffer> offers = new ArrayList<CandidateOffer>();
        for (ScopeAdviceCandidate candidate : advice.getQuestionCandidates()) {
            offers.add(new CandidateOffer(candidate.getCandidateId(), candidate.getReason(),
                    candidate.getProbeText(), contextNoteOf(candidate, anchorTexts)));
        }
        List<String> guards = new ArrayList<String>();
        for (ScopeDriftGuard guard : advice.getDriftGuards()) {
            String outText = anchorTexts.get(guard.getNearestOutAnchorId());
            guards.add("\"" + guard.getProbeText() + "\" bleibt bewusst ausgeschlossen"
                    + (outText == null ? "" : " (Nähe zum Ausschluss: " + outText + ")"));
        }
        return new ChoiceRequest(draft.getMission(), offers, guards);
    }

    private static String contextNoteOf(ScopeAdviceCandidate candidate,
                                        Map<String, String> anchorTexts) {
        switch (candidate.getReason()) {
            case RESOLVE_PENDING:
                return prefixed("bereits angesprochen über: ",
                        anchorTexts.get(candidate.getNearestProvisionalAnchorId()));
            case CLARIFY_BOUNDARY: {
                String in = anchorTexts.get(candidate.getNearestInAnchorId());
                String out = anchorTexts.get(candidate.getNearestOutAnchorId());
                StringBuilder note = new StringBuilder();
                if (in != null) {
                    note.append("aufgenommene Seite: ").append(in);
                }
                if (out != null) {
                    if (note.length() > 0) {
                        note.append("; ");
                    }
                    note.append("ausgeschlossene Seite: ").append(out);
                }
                return note.toString();
            }
            case CHECK_IN_EXTENSION:
                return prefixed("Rand der bereits aufgenommenen Region: ",
                        anchorTexts.get(candidate.getNearestInAnchorId()));
            default:
                return "noch von keinem Zaunpfosten abgedeckt";
        }
    }

    private static String prefixed(String prefix, String anchorText) {
        return anchorText == null ? "" : prefix + anchorText;
    }
}
