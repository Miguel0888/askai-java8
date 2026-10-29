package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where the research controls live: the web search is the host's TITLE-BAR search contribution, the
 * icon-only language switch is the only top-bar control (trailing), the phase selector lives in the
 * State pane footer.
 */
public class ResearchWebSearchToolbarContributionTest {

    @Test
    public void theWebSearchIsTheTitleBarSearchContribution() {
        com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution search =
                new com.aresstack.askai.research.plugin.ResearchAgentPluginExtension().getSearchContribution();
        assertTrue(search instanceof ResearchWebSearchContribution);
        assertEquals("Web search…", search.getPlaceholder());
        assertFalse("only research sessions get the web search", search.supports(null));
        search.search(null, "ignored"); // a non-research session is a no-op, never a crash
    }

    @Test
    public void thePhaseSelectorIsNoLongerAToolbarContribution() {
        for (AgentToolbarContribution registered
                : new com.aresstack.askai.research.plugin.ResearchAgentPluginExtension().getToolbarContributions()) {
            assertFalse("the phase selector lives in the State pane footer now",
                    registered instanceof ResearchPhaseToolbarContribution);
        }
    }

    @Test
    public void thePhaseSelectorComponentKeepsItsIdentity() {
        ResearchPhaseToolbarContribution contribution = new ResearchPhaseToolbarContribution();
        assertEquals("research-phase-selector", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.SIDEBAR_FOOTER,
                contribution.getPlacement());
        assertFalse("only research sessions get the selector", contribution.supports(null));
    }

    @Test
    public void theLanguageSwitchIsTheOnlyTrailingControl() {
        assertEquals(AgentToolbarContribution.Placement.TRAILING,
                new ResearchLanguageToolbarContribution().getPlacement());
        int trailing = 0;
        for (AgentToolbarContribution registered
                : new com.aresstack.askai.research.plugin.ResearchAgentPluginExtension().getToolbarContributions()) {
            if (registered.getPlacement() == AgentToolbarContribution.Placement.TRAILING) {
                trailing++;
                assertTrue(registered instanceof ResearchLanguageToolbarContribution);
            }
        }
        assertEquals("the language switch is the only top-bar control", 1, trailing);
    }
}
