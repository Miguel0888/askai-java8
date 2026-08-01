package com.aresstack.askai.research.runtime.team;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Selects the {@link PhaseAssistantProfile} for the active phase (RA-P6 §6/§48). The active phase is decided
 * only by the application's state machine and reaches the runtime as the live phase id
 * ({@code TeamAgentStateView.getPhaseId()}); this registry maps that id to a profile, falling back to a
 * neutral phase-agnostic profile for phases that do not yet have their own. No big-bang migration: phases
 * gain tailored profiles one at a time, and until then they behave sensibly through the fallback.
 */
public final class PhaseAssistantProfileRegistry {

    /** The existing repository phase id for the first phase; reuse it, do not invent a new taxonomy. */
    public static final String SCOPING_PHASE_ID = "scoping";

    private final Map<String, PhaseAssistantProfile> byPhaseId;
    private final PhaseAssistantProfile fallback;

    public PhaseAssistantProfileRegistry(Map<String, PhaseAssistantProfile> profiles,
                                         PhaseAssistantProfile fallback) {
        if (fallback == null) {
            throw new IllegalArgumentException("fallback profile must not be null");
        }
        Map<String, PhaseAssistantProfile> copy = new LinkedHashMap<String, PhaseAssistantProfile>();
        if (profiles != null) {
            for (Map.Entry<String, PhaseAssistantProfile> entry : profiles.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        this.byPhaseId = Collections.unmodifiableMap(copy);
        this.fallback = fallback;
    }

    /**
     * The default registry: a productive SCOPING profile (least privilege — no capabilities/tools yet, writes
     * only the research-brief artifact) plus a neutral fallback for every other phase.
     */
    public static PhaseAssistantProfileRegistry defaults() {
        PhaseAssistantProfile scoping = new PhaseAssistantProfile(
                SCOPING_PHASE_ID,
                TeamAgentPlaybook.scopingSystemPrompt(),
                "research-brief",
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS);
        PhaseAssistantProfile fallback = new PhaseAssistantProfile(
                "",
                TeamAgentPlaybook.defaultSystemPrompt(),
                "",
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS);
        Map<String, PhaseAssistantProfile> profiles = new LinkedHashMap<String, PhaseAssistantProfile>();
        profiles.put(SCOPING_PHASE_ID, scoping);
        return new PhaseAssistantProfileRegistry(profiles, fallback);
    }

    /** The profile for {@code phaseId} (case-insensitive), or the neutral fallback when none is registered. */
    public PhaseAssistantProfile forPhase(String phaseId) {
        if (phaseId == null) {
            return fallback;
        }
        PhaseAssistantProfile profile = byPhaseId.get(phaseId.trim().toLowerCase(Locale.ROOT));
        return profile == null ? fallback : profile;
    }
}
