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
        assertEquals(VisualizationResult.Kind.NONE, r.getKind());
        assertEquals("not enough structure yet", r.getReason());
    }

    @Test
    public void aTypeTokenInTheDecisionStillCountsAsADiagram() {
        // Seen live with gemma: {"decision":"MINDMAP", ...} — the type lands where DIAGRAM belongs.
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"MINDMAP\",\"diagramType\":\"MINDMAP\",\"title\":\"Wearables\","
                        + "\"mermaid\":\"mindmap\\n  Wearables\\n    Audio\\n    Video\"}");
        assertTrue("a non-NONE decision with a diagram body is a diagram", r.isPresent());
        assertEquals(VisualizationType.MINDMAP, r.getType());
    }

    @Test
    public void aDecisionTokenAloneSuppliesTheTypeWhenDiagramTypeIsMissing() {
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"TIMELINE\",\"mermaid\":\"timeline\\n 2020 : a\"}");
        assertTrue(r.isPresent());
        assertEquals(VisualizationType.TIMELINE, r.getType());
    }

    @Test
    public void anUnknownDecisionWithoutMermaidIsStillAValidNone() {
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"maybe\",\"reason\":\"unsure\"}");
        assertFalse(r.isPresent());
        assertEquals(VisualizationResult.Kind.NONE, r.getKind());
        assertEquals("unsure", r.getReason());
    }

    @Test
    public void aDiagramDecisionWithoutMermaidIsAFailureNotADeliberateNone() {
        VisualizationResult r = VisualizationResultParser.parse("{\"decision\":\"DIAGRAM\",\"mermaid\":\"  \"}");
        assertFalse(r.isPresent());
        assertEquals(VisualizationResult.Kind.FAILED, r.getKind());
    }

    @Test
    public void anUnknownDiagramTypeFallsBackToGraph() {
        VisualizationResult r = VisualizationResultParser.parse(
                "{\"decision\":\"DIAGRAM\",\"diagramType\":\"quadrant\",\"mermaid\":\"graph LR\\n A-->B\"}");
        assertTrue(r.isPresent());
        assertEquals(VisualizationType.GRAPH, r.getType());
    }

    @Test
    public void malformedOutputNeverThrowsAndDegradesToFailed() {
        assertEquals(VisualizationResult.Kind.FAILED,
                VisualizationResultParser.parse("not json at all").getKind());
        assertEquals(VisualizationResult.Kind.FAILED,
                VisualizationResultParser.parse("{ broken").getKind());
        assertEquals(VisualizationResult.Kind.FAILED,
                VisualizationResultParser.parse(null).getKind());
    }
}
