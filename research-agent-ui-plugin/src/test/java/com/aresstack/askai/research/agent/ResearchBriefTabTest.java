package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.research.store.ResearchBriefArtifact;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The Fragestellung (research brief) is a real artifact tab wired to its own view contribution. */
public class ResearchBriefTabTest {

    @Test
    public void theBriefIsTheFirstArtifactWithItsOwnType() {
        AgentArtifact first = ResearchArtifacts.all().get(0);
        assertEquals(ResearchBriefArtifact.ARTIFACT_ID, first.getId());
        assertEquals("Fragestellung", first.getDisplayName());
        assertEquals(ResearchArtifacts.TYPE_BRIEF, first.getArtifactTypeId());
        assertEquals(ResearchArtifacts.TYPE_BRIEF,
                new ResearchBriefViewContribution().getArtifactTypeId());
        assertEquals("Fragestellung", new ResearchBriefViewContribution().getDisplayName());
    }
}
