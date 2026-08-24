package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The "Websuche" tag moved from the scoping tag surface into the workspace TOP BAR: a CENTERED
 * toolbar contribution, so the host places it between ribbon and trailing controls.
 */
public class ResearchWebSearchToolbarContributionTest {

    @Test
    public void theWebSearchTagIsACenteredToolbarContribution() {
        ResearchWebSearchToolbarContribution contribution =
                new ResearchWebSearchToolbarContribution();
        assertEquals("research-web-search", contribution.getId());
        assertEquals(AgentToolbarContribution.Placement.CENTER, contribution.getPlacement());
        assertFalse("only research sessions get the tag", contribution.supports(null));
    }

    @Test
    public void theLanguageSwitchStaysTrailing() {
        assertEquals(AgentToolbarContribution.Placement.TRAILING,
                new ResearchLanguageToolbarContribution().getPlacement());
    }
}
