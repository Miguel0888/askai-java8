package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The active phase selects a phase-specific assistant profile, with a neutral fallback for the rest. */
public class PhaseAssistantProfileRegistryTest {

    @Test
    public void scopingHasItsOwnLeastPrivilegeProfile() {
        PhaseAssistantProfile scoping = PhaseAssistantProfileRegistry.defaults().forPhase("scoping");

        assertEquals("scoping", scoping.getPhaseId());
        assertTrue(scoping.getSystemPrompt().contains("sharpen their research scope"));
        assertEquals("research-brief", scoping.getWritableArtifactId());
        assertTrue("least privilege: the scoping assistant has no tools yet",
                scoping.getAllowedCapabilities().isEmpty());
        assertEquals(PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS, scoping.getContextPolicy());
    }

    @Test
    public void phaseLookupIsCaseInsensitive() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        assertEquals(registry.forPhase("scoping").getSystemPrompt(),
                registry.forPhase("SCOPING").getSystemPrompt());
    }

    @Test
    public void unknownAndNullPhasesGetTheNeutralFallback() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        String scopingPrompt = registry.forPhase("scoping").getSystemPrompt();

        PhaseAssistantProfile fallback = registry.forPhase("outline");
        assertTrue(fallback.getSystemPrompt().contains("working alongside the user within the current "
                + "research phase"));
        assertNotEquals(scopingPrompt, fallback.getSystemPrompt());
        assertEquals(fallback.getSystemPrompt(), registry.forPhase(null).getSystemPrompt());
    }

    @Test
    public void everyProfilePromptKeepsTheSameOutputContract() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        assertTrue(registry.forPhase("scoping").getSystemPrompt().contains("\"assistantMessage\": string"));
        assertTrue(registry.forPhase("outline").getSystemPrompt().contains("\"assistantMessage\": string"));
        assertFalse("no workflow machinery leaks into a phase prompt",
                registry.forPhase("scoping").getSystemPrompt().contains("readyForBrief"));
    }
}
