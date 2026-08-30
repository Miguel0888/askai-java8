package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.research.store.ResearchBriefArtifact;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The Konzept tab (research brief, formerly "Fragestellung") is a real artifact tab with its own view. */
public class ResearchBriefTabTest {

    @Test
    public void theBriefIsTheFirstArtifactWithItsOwnType() {
        AgentArtifact first = ResearchArtifacts.all().get(0);
        assertEquals(ResearchBriefArtifact.ARTIFACT_ID, first.getId());
        assertEquals("Concept", first.getDisplayName());
        assertEquals(ResearchArtifacts.TYPE_BRIEF, first.getArtifactTypeId());
        assertEquals(ResearchArtifacts.TYPE_BRIEF,
                new ResearchBriefViewContribution().getArtifactTypeId());
        assertEquals("Concept", new ResearchBriefViewContribution().getDisplayName());
    }
}
