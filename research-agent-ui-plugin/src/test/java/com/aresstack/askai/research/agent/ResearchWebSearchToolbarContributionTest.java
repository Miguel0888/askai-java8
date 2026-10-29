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
    public void theWebSearchTagSitsCenteredAndTheVisualizeButtonsTrail() {
        ResearchWebSearchToolbarContribution contribution =
                new ResearchWebSearchToolbarContribution();
        assertEquals("research-web-search", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.CENTER, contribution.getPlacement());
        assertEquals(AgentToolbarContribution.Placement.TRAILING,
                new ResearchOverlayButtonsToolbarContribution().getPlacement());
        assertFalse("only research sessions get the tag", contribution.supports(null));
    }

    @Test
    public void thePhaseSelectorLivesInTheChatsFooterBesideTheLanguagePill() {
        ResearchPhaseToolbarContribution contribution = new ResearchPhaseToolbarContribution();
        assertEquals("research-phase-selector", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.SIDEBAR_FOOTER,
                contribution.getPlacement());
        assertFalse("only research sessions get the selector", contribution.supports(null));
    }

    @Test
    public void theLanguageSwitchTrailsRightOfTheVisualizeButtons() {
        assertEquals(AgentToolbarContribution.Placement.TRAILING,
                new ResearchLanguageToolbarContribution().getPlacement());
        java.util.List<AgentToolbarContribution> all =
                new com.aresstack.askai.research.plugin.ResearchAgentPluginExtension().getToolbarContributions();
        int visualize = -1;
        int language = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof ResearchOverlayButtonsToolbarContribution) {
                visualize = i;
            } else if (all.get(i) instanceof ResearchLanguageToolbarContribution) {
                language = i;
            }
        }
        assertTrue("same TRAILING slot, laid out in list order: language right of visualize",
                visualize >= 0 && language == visualize + 1);
    }
}
