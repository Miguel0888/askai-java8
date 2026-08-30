package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The design-study top-bar order: the PHASE SELECTOR holds the centered spot (the Websuche's old
 * place), the "Websuche" tag (+ mindmap button) trails at the far right, and the session language
 * switch lives in the drawer's CHATS FOOTER — not in the top bar at all.
 */
public class ResearchWebSearchToolbarContributionTest {

    @Test
    public void theWebSearchTagTrailsAtTheFarRight() {
        ResearchWebSearchToolbarContribution contribution =
                new ResearchWebSearchToolbarContribution();
        assertEquals("research-web-search", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.TRAILING, contribution.getPlacement());
        assertFalse("only research sessions get the tag", contribution.supports(null));
    }

    @Test
    public void thePhaseSelectorLeadsRightAfterTheBurger() {
        ResearchPhaseToolbarContribution contribution = new ResearchPhaseToolbarContribution();
        assertEquals("research-phase-selector", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.LEADING, contribution.getPlacement());
        assertFalse("only research sessions get the selector", contribution.supports(null));
    }

    @Test
    public void theLanguageSwitchLivesInTheChatsFooter() {
        assertEquals(AgentToolbarContribution.Placement.SIDEBAR_FOOTER,
                new ResearchLanguageToolbarContribution().getPlacement());
    }
}
