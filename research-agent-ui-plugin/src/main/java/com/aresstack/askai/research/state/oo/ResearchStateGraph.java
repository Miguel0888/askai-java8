package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.aresstack.askai.research.state.oo.ResearchStateIds.DRAFT;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.EVIDENCE;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.FINALIZATION;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.OUTLINE;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.REVIEW;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING;

/**
 * The <em>forward</em> (phase-progressing) transition data of the research lifecycle: which command, from which
 * (phase, state), leads to which (phase, state), and whether the target is an approval gate. This is plain data
 * consulted by each state — not a runtime switch. Interruptions (pause/block/fail/cancel/resume/unblock/retry)
 * are intrinsic to the inner states and are NOT part of this graph.
 */
final class ResearchStateGraph {

    static final class Edge {
        final String targetPhaseId;
        final String targetStateId;

        Edge(String targetPhaseId, String targetStateId) {
            this.targetPhaseId = targetPhaseId;
            this.targetStateId = targetStateId;
        }
    }

    private static final Map<String, Edge> EDGES = new LinkedHashMap<String, Edge>();
    private static final Set<String> KNOWN_COMBOS = new LinkedHashSet<String>();

    static {
        // SCOPING
        edge(SCOPING, ResearchStateIds.NEW, ResearchCommandType.START, SCOPING, ResearchStateIds.RUNNING);
        // C5: NO pre-research outline approval — a confirmed scope goes STRAIGHT to research; the live
        // outline is a mobile projection of the growing corpus. The OUTLINE phase below is deliberately KEPT:
        // persisted old sessions sitting in OUTLINE stay operable, and the phase returns later as the
        // post-evidence freeze/approval step before drafting.
        edge(SCOPING, ResearchStateIds.RUNNING, ResearchCommandType.SUBMIT_SCOPE,
                RESEARCH, ResearchStateIds.WAITING);
        // OUTLINE
        edge(OUTLINE, ResearchStateIds.RUNNING, ResearchCommandType.PROPOSE_OUTLINE,
                OUTLINE, ResearchStateIds.WAITING_APPROVAL);
        edge(OUTLINE, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_OUTLINE,
                RESEARCH, ResearchStateIds.WAITING);
        edge(OUTLINE, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.REQUEST_OUTLINE_CHANGES,
                OUTLINE, ResearchStateIds.RUNNING);
        // RESEARCH
        edge(RESEARCH, ResearchStateIds.WAITING, ResearchCommandType.START_RESEARCH,
                RESEARCH, ResearchStateIds.RUNNING);
        edge(RESEARCH, ResearchStateIds.RUNNING, ResearchCommandType.REQUEST_EVIDENCE_REVIEW,
                EVIDENCE, ResearchStateIds.WAITING_APPROVAL);
        // EVIDENCE
        edge(EVIDENCE, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_EVIDENCE,
                DRAFT, ResearchStateIds.WAITING);
        edge(EVIDENCE, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.REQUEST_REVISION,
                RESEARCH, ResearchStateIds.RUNNING);
        // DRAFT
        edge(DRAFT, ResearchStateIds.WAITING, ResearchCommandType.START_DRAFTING,
                DRAFT, ResearchStateIds.RUNNING);
        edge(DRAFT, ResearchStateIds.RUNNING, ResearchCommandType.REQUEST_DRAFT_REVIEW,
                REVIEW, ResearchStateIds.WAITING_APPROVAL);
        // REVIEW
        edge(REVIEW, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_DRAFT,
                FINALIZATION, ResearchStateIds.RUNNING);
        edge(REVIEW, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.REQUEST_REVISION,
                DRAFT, ResearchStateIds.RUNNING);
        // FINALIZATION
        edge(FINALIZATION, ResearchStateIds.RUNNING, ResearchCommandType.REQUEST_FINAL_REVIEW,
                FINALIZATION, ResearchStateIds.WAITING_APPROVAL);
        edge(FINALIZATION, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_FINAL,
                FINALIZATION, ResearchStateIds.COMPLETED);
    }

    private ResearchStateGraph() {
    }

    private static void edge(String phaseId, String stateId, ResearchCommandType command,
                             String targetPhaseId, String targetStateId) {
        EDGES.put(key(phaseId, stateId, command), new Edge(targetPhaseId, targetStateId));
        KNOWN_COMBOS.add(phaseId + "|" + stateId);
        KNOWN_COMBOS.add(targetPhaseId + "|" + targetStateId);
    }

    /** @return whether (phase, base-state) appears anywhere in the forward graph (source or target). */
    static boolean isKnownCombo(String phaseId, String stateId) {
        return KNOWN_COMBOS.contains(phaseId + "|" + stateId);
    }

    private static String key(String phaseId, String stateId, ResearchCommandType command) {
        return phaseId + "|" + stateId + "|" + command;
    }

    /** @return the forward edge for this (phase, state, command), or {@code null} if none. */
    static Edge forward(String phaseId, String stateId, ResearchCommandType command) {
        return EDGES.get(key(phaseId, stateId, command));
    }

    /** @return the set of forward commands available from this (phase, state). */
    static Set<ResearchCommandType> forwardCommands(String phaseId, String stateId) {
        Set<ResearchCommandType> commands = new LinkedHashSet<ResearchCommandType>();
        String prefix = phaseId + "|" + stateId + "|";
        for (Map.Entry<String, Edge> entry : EDGES.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                commands.add(ResearchCommandType.valueOf(entry.getKey().substring(prefix.length())));
            }
        }
        return Collections.unmodifiableSet(commands);
    }
}
