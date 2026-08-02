package com.aresstack.askai.research.visualize;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The visualizer output contract: DIAGRAM vs the always-valid NONE, and non-throwing tolerance. */
public class VisualizationResultParserTest {

    @Test
    public void aDiagramDecisionYieldsATypedDiagram() {
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"DIAGRAM\",\"diagramType\":\"flowchart\","
                        + "\"title\":\"Struktur der Fragestellung\",\"mermaid\":\"flowchart TD\\n A --> B\"}");
        assertTrue(r.isPresent());
        assertEquals(VisualizationType.FLOWCHART, r.getType());
        assertEquals("Struktur der Fragestellung", r.getTitle());
        assertTrue(r.getMermaid().startsWith("flowchart"));
    }

    @Test
    public void aNoneDecisionIsAValidResultWithAReason() {
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"NONE\",\"reason\":\"not enough structure yet\"}");
        assertFalse(r.isPresent());
        assertEquals("not enough structure yet", r.getReason());
    }

    @Test
    public void aDiagramDecisionWithoutMermaidDegradesToNone() {
        VisualizationResult r = VisualizationResultParser.parse("{\"decision\":\"DIAGRAM\",\"mermaid\":\"  \"}");
        assertFalse(r.isPresent());
    }

    @Test
    public void anUnknownDiagramTypeFallsBackToGraph() {
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"DIAGRAM\",\"diagramType\":\"quadrant\",\"mermaid\":\"graph LR\\n A-->B\"}");
        assertTrue(r.isPresent());
        assertEquals(VisualizationType.GRAPH, r.getType());
    }

    @Test
    public void malformedOutputNeverThrowsAndDegradesToNone() {
        assertFalse(VisualizationResultParser.parse("not json at all").isPresent());
        assertFalse(VisualizationResultParser.parse("{ broken").isPresent());
        assertFalse(VisualizationResultParser.parse(null).isPresent());
    }
}
