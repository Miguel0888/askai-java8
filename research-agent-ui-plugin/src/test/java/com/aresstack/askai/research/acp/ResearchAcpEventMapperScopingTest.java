package com.aresstack.askai.research.acp;

import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A scoping projection wire line decodes into a typed SCOPING_PROJECTION event (map + suggestions + advice). */
public class ResearchAcpEventMapperScopingTest {

    private static ResearchBackendEvent map(String wireLine) {
        return ResearchAcpEventMapper.mapUpdate(
                new AcpUpdate("s1", "p1", 1L, AcpUpdate.Kind.MESSAGE, wireLine)).build();
    }

    @Test
    public void aScopeassistLineBecomesATypedProjection() {
        ResearchBackendEvent event = map("#RSX1# scopeassist phase=scoping map=mindmap advice=CONTINUE "
                + "advicereason=precise sugg=wearables|tech|1,glasses|priv|2");

        assertEquals(ResearchBackendEventType.SCOPING_PROJECTION, event.getType());
        ScopingAssistantUpdate projection = event.getScopingProjection();
        assertEquals("scoping", projection.getPhaseId());
        assertEquals("mindmap", projection.getExplorationMapMermaid());
        assertTrue(projection.hasExplorationMap());
        assertEquals("CONTINUE", projection.getAdviceRecommendation());
        assertEquals(2, projection.getSearchSuggestions().size());
        assertEquals("wearables", projection.getSearchSuggestions().get(0).getQuery());
        assertEquals("tech", projection.getSearchSuggestions().get(0).getPurpose());
        assertEquals(1, projection.getSearchSuggestions().get(0).getPriority());
        assertEquals("glasses", projection.getSearchSuggestions().get(1).getQuery());
    }

    @Test
    public void anEmptyProjectionClearsTheWorkspacePanels() {
        ResearchBackendEvent event = map("#RSX1# scopeassist phase=scoping advice=NEUTRAL");

        assertEquals(ResearchBackendEventType.SCOPING_PROJECTION, event.getType());
        ScopingAssistantUpdate projection = event.getScopingProjection();
        assertFalse("no exploration map -> panel stays empty", projection.hasExplorationMap());
        assertTrue(projection.getSearchSuggestions().isEmpty());
    }
}
