package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE table of the semantic USER command vocabulary: semantic name → the concrete
 * {@link ResearchCommandType} candidates it resolves against (first allowed wins). Red action
 * tags, slash commands, the MCP {@code run_command} vocabulary, the State tab's phase clicks and
 * {@link ResearchAgentSession#executeCommand} all project from exactly this map — internal enum
 * names are never user API, and there is no second action matrix anywhere.
 */
final class ResearchSemanticCommands {

    private static final Map<String, List<ResearchCommandType>> CANDIDATES;

    static {
        Map<String, List<ResearchCommandType>> map =
                new LinkedHashMap<String, List<ResearchCommandType>>();
        map.put("submit-scope", Arrays.asList(ResearchCommandType.SUBMIT_SCOPE));
        map.put("approve", Arrays.asList(ResearchCommandType.APPROVE_OUTLINE,
                ResearchCommandType.APPROVE_EVIDENCE, ResearchCommandType.APPROVE_DRAFT,
                ResearchCommandType.APPROVE_FINAL));
        map.put("request-changes", Arrays.asList(ResearchCommandType.REQUEST_OUTLINE_CHANGES,
                ResearchCommandType.REQUEST_REVISION));
        map.put("continue", Arrays.asList(ResearchCommandType.START_RESEARCH,
                ResearchCommandType.START_DRAFTING));
        map.put("retry", Arrays.asList(ResearchCommandType.RETRY));
        map.put("resume", Arrays.asList(ResearchCommandType.RESUME, ResearchCommandType.UNBLOCK));
        map.put("pause", Arrays.asList(ResearchCommandType.PAUSE));
        map.put("cancel", Arrays.asList(ResearchCommandType.CANCEL));
        CANDIDATES = Collections.unmodifiableMap(map);
    }

    private ResearchSemanticCommands() {
    }

    /** All semantic command names, in stable declaration order. */
    static List<String> names() {
        return new ArrayList<String>(CANDIDATES.keySet());
    }

    /** The concrete candidates for a semantic name, or an empty list for an unknown name. */
    static List<ResearchCommandType> candidates(String name) {
        List<ResearchCommandType> candidates = CANDIDATES.get(name);
        return candidates == null ? Collections.<ResearchCommandType>emptyList() : candidates;
    }

    /** The semantic USER name a concrete command belongs to, or {@code null} for agent-internal ones. */
    static String semanticNameFor(ResearchCommandType type) {
        for (Map.Entry<String, List<ResearchCommandType>> entry : CANDIDATES.entrySet()) {
            if (entry.getValue().contains(type)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
