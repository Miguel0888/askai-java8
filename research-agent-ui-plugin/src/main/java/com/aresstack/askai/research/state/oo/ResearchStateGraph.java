package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.aresstack.askai.research.state.oo.ResearchStateIds.DRAFT;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.OUTLINE;
import static com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH;
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
        // #43: the ratified 4-phase product model — Concept (id "scoping") → Sources (id
        // "research") → Outline → Document (id "draft"). Technical ids stay the persisted
        // compat vocabulary; evidence review, drafting review and finalization are ACTIVITIES
        // inside Sources/Outline/Document, never phases of their own.
        // CONCEPT
        edge(SCOPING, ResearchStateIds.NEW, ResearchCommandType.START, SCOPING, ResearchStateIds.RUNNING);
        edge(SCOPING, ResearchStateIds.RUNNING, ResearchCommandType.SUBMIT_SCOPE,
                RESEARCH, ResearchStateIds.WAITING);
        // SOURCES — the evidence review is ITS closing approval gate now.
        edge(RESEARCH, ResearchStateIds.WAITING, ResearchCommandType.START_RESEARCH,
                RESEARCH, ResearchStateIds.RUNNING);
        edge(RESEARCH, ResearchStateIds.RUNNING, ResearchCommandType.REQUEST_EVIDENCE_REVIEW,
                RESEARCH, ResearchStateIds.WAITING_APPROVAL);
        edge(RESEARCH, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_EVIDENCE,
                OUTLINE, ResearchStateIds.RUNNING);
        edge(RESEARCH, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.REQUEST_REVISION,
                RESEARCH, ResearchStateIds.RUNNING);
        // OUTLINE — AFTER Sources: the structure is developed from concept + research corpus.
        edge(OUTLINE, ResearchStateIds.RUNNING, ResearchCommandType.PROPOSE_OUTLINE,
                OUTLINE, ResearchStateIds.WAITING_APPROVAL);
        edge(OUTLINE, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_OUTLINE,
                DRAFT, ResearchStateIds.WAITING);
        edge(OUTLINE, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.REQUEST_OUTLINE_CHANGES,
                OUTLINE, ResearchStateIds.RUNNING);
        // DOCUMENT — drafting, review and finalization live INSIDE this one phase.
        edge(DRAFT, ResearchStateIds.WAITING, ResearchCommandType.START_DRAFTING,
                DRAFT, ResearchStateIds.RUNNING);
        edge(DRAFT, ResearchStateIds.RUNNING, ResearchCommandType.REQUEST_DRAFT_REVIEW,
                DRAFT, ResearchStateIds.WAITING_APPROVAL);
        edge(DRAFT, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.REQUEST_REVISION,
                DRAFT, ResearchStateIds.RUNNING);
        edge(DRAFT, ResearchStateIds.WAITING_APPROVAL, ResearchCommandType.APPROVE_FINAL,
                DRAFT, ResearchStateIds.COMPLETED);
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
