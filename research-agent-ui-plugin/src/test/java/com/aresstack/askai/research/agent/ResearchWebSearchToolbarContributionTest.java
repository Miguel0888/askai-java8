package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The design-study top-bar order: the PHASE SELECTOR holds the centered spot (the Websuche's old
 * place), the "Websuche" tag (+ mindmap button) trails at the far right, and the session language
 * switch lives in the drawer's CHATS FOOTER — not in the top bar at all.
 */
public class ResearchWebSearchToolbarContributionTest {

    @Test
    public void theWebSearchTagSitsCentered() {
        ResearchWebSearchToolbarContribution contribution =
                new ResearchWebSearchToolbarContribution();
        assertEquals("research-web-search", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.CENTER, contribution.getPlacement());
        assertFalse("only research sessions get the tag", contribution.supports(null));
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
        assertEquals("the visualize buttons moved into the Concept/Sources pane footers", 1, trailing);
    }
}
