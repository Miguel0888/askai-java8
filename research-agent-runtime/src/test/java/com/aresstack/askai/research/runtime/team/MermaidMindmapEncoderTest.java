package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The app owns the Mermaid syntax: a structured map always renders to a valid mindmap. */
public class MermaidMindmapEncoderTest {

    @Test
    public void aStructuredMapRendersAsAValidIndentedMindmap() {
        ExplorationMap map = new ExplorationMap(new ExplorationNode("Wearables", Arrays.asList(
                new ExplorationNode("Multimedia Integration", Arrays.asList(
                        new ExplorationNode("Audio Features", null),
                        new ExplorationNode("Video Features", null))),
                new ExplorationNode("Device Interface", Collections.<ExplorationNode>emptyList()))));

        String mermaid = MermaidMindmapEncoder.encode(map);

        assertEquals("mindmap\n"
                + "  root((Wearables))\n"
                + "    Multimedia Integration\n"
                + "      Audio Features\n"
                + "      Video Features\n"
                + "    Device Interface\n", mermaid);
    }

    @Test
    public void nodeLabelsAreSanitizedOfMermaidShapeCharacters() {
        // An indented-outline-style label with brackets/parens must not break the diagram syntax.
        ExplorationMap map = new ExplorationMap(new ExplorationNode("Wearables (2026)", Arrays.asList(
                new ExplorationNode("AR/VR [video]", null))));

        String mermaid = MermaidMindmapEncoder.encode(map);

        assertTrue(mermaid.startsWith("mindmap\n  root((Wearables 2026))"));
        assertTrue(mermaid.contains("AR/VR video"));
        assertFalse(mermaid.contains("[video]"));
    }
}
