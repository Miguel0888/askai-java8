package com.aresstack.askai.research.runtime.team;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The technically-enclosed assistant of ONE research phase (RA-P6 §6/§7). Rather than a single monolithic
 * system prompt that tries to govern every phase, each phase has its own profile: its own system prompt, the
 * one artifact it may WRITE, the artifacts it may READ, its allowed capabilities (Principle of Least Privilege)
 * and its context policy. The active phase — decided solely by the application's state machine — selects the
 * profile, and the profile decides the prompt and (later) the tools and readable/writable artifacts.
 *
 * <p>In this first slice only {@link #getSystemPrompt()} is consumed productively; the artifact ids, the
 * capability set and the {@link PhaseContextPolicy} are carried as declared metadata (forward hooks) so a
 * phase can already state its intended privileges before artifacts, tools and context filtering are wired.</p>
 */
public final class PhaseAssistantProfile {

    private final String phaseId;
    private final String systemPrompt;
    private final String writableArtifactId;
    private final List<String> readableArtifactIds;
    private final Set<String> allowedCapabilities;
    private final PhaseContextPolicy contextPolicy;

    public PhaseAssistantProfile(String phaseId, String systemPrompt, String writableArtifactId,
                                 List<String> readableArtifactIds, Set<String> allowedCapabilities,
                                 PhaseContextPolicy contextPolicy) {
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("systemPrompt must not be empty");
        }
        this.phaseId = phaseId == null ? "" : phaseId.trim();
        this.systemPrompt = systemPrompt;
        this.writableArtifactId = writableArtifactId == null ? "" : writableArtifactId.trim();
        this.readableArtifactIds = immutableStrings(readableArtifactIds);
        this.allowedCapabilities = immutableSet(allowedCapabilities);
        this.contextPolicy = contextPolicy == null
                ? PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS : contextPolicy;
    }

    /** The phase this profile serves (matched case-insensitively against the live host phase id). */
    public String getPhaseId() {
        return phaseId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** The single artifact this phase may write (forward hook — not enforced yet). Empty when none. */
    public String getWritableArtifactId() {
        return writableArtifactId;
    }

    /** The artifacts this phase may read (forward hook — not enforced yet). */
    public List<String> getReadableArtifactIds() {
        return readableArtifactIds;
    }

    /** The capabilities/tools this phase's assistant is allowed (forward hook — least privilege, empty now). */
    public Set<String> getAllowedCapabilities() {
        return allowedCapabilities;
    }

    public PhaseContextPolicy getContextPolicy() {
        return contextPolicy;
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.List<String> copy = new java.util.ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static Set<String> immutableSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(copy);
    }
}
