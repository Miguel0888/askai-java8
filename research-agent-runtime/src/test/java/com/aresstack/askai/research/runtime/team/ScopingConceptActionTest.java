package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The hardened concept contract: paths are SEGMENT ARRAYS, never slash-joined strings — a name
 * is one label ('/' inside it stays a character), a bare string counts as exactly one segment.
 * {@code none}/absent both mean "this turn touches nothing"; malformed actions carry their
 * reason back with an example; the canonical history round-trips the segments.
 */
public class ScopingConceptActionTest {

    private static ScopingAssistantOutput parse(String json) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(json);
        assertTrue("must parse: " + result.getError(), result.isOk());
        return result.getOutput();
    }

    @Test
    public void readAddAndRemoveParseWithSegmentArrays() {
        ConceptAction read = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"read\",\"path\":[\"FreeRTOS\",\"Kommunikation\"]}}")
                .getConceptAction();
        assertEquals(ConceptAction.Type.READ, read.getType());
        assertEquals(Arrays.asList("FreeRTOS", "Kommunikation"), read.getPath());

        ConceptAction add = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add\",\"parent\":[\"FreeRTOS\"],\"name\":\"TCP/IP\"}}")
                .getConceptAction();
        assertEquals(ConceptAction.Type.ADD, add.getType());
        assertEquals(Collections.singletonList("FreeRTOS"), add.getParent());
        assertEquals("a slash inside a NAME is just a character", "TCP/IP", add.getName());

        ConceptAction remove = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"remove\",\"path\":[\"FreeRTOS\",\"Praxis\",\"ESP-IDF\"]}}")
                .getConceptAction();
        assertEquals(Arrays.asList("FreeRTOS", "Praxis", "ESP-IDF"), remove.getPath());
    }

    @Test
    public void aBareStringIsExactlyOneSegmentNeverSplit() {
        ConceptAction read = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"read\",\"path\":\"FreeRTOS/ESP32\"}}").getConceptAction();
        assertEquals("never split on '/' — one segment, whatever it contains",
                Collections.singletonList("FreeRTOS/ESP32"), read.getPath());
    }

    @Test
    public void noneAndAbsentBothMeanNoAction() {
        ScopingAssistantOutput none = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"none\"}}");
        assertNull(none.getConceptAction());
        assertNull(none.getConceptActionError());
        ScopingAssistantOutput absent = parse("{\"assistantMessage\":\"m\"}");
        assertNull(absent.getConceptAction());
        assertTrue("an action-free turn stays byte-identical to before",
                !absent.canonicalJson().contains("conceptAction"));
    }

    @Test
    public void malformedActionsCarryTheirReasonWithASegmentExample() {
        ScopingAssistantOutput missingName = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"add\",\"parent\":[\"FreeRTOS\"]}}");
        assertNull(missingName.getConceptAction());
        assertTrue(missingName.getConceptActionError().contains("requires \"name\""));
        assertTrue("the example teaches segments",
                missingName.getConceptActionError().contains("\"parent\":[\"FreeRTOS\"]"));

        ScopingAssistantOutput missingPath = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"remove\"}}");
        assertTrue(missingPath.getConceptActionError().contains("requires \"path\""));

        ScopingAssistantOutput unknownType = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"update\"}}");
        assertTrue(unknownType.getConceptActionError().contains("unknown type"));
    }

    /** The codec's "ALL fields" claim finally holds: the scope proposal round-trips too. */
    @Test
    public void theCanonicalHistoryRoundTripsTheScopePatch() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\","
                + "\"scopePatch\":{\"operations\":[{\"kind\":\"addExclusion\","
                + "\"value\":\"ESP-IDF\"}]},"
                + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(output.getScopeUpdate() != null && output.getScopeUpdate().isValid());
        ScopingAssistantOutput reread = parse(output.canonicalJson());
        assertTrue("the exclusion survives the history round-trip",
                reread.getScopeUpdate() != null && reread.getScopeUpdate().isValid()
                        && reread.getScopeUpdate().toJson().contains("ESP-IDF"));
    }

    @Test
    public void theCanonicalHistoryRoundTripsTheSegments() {
        ScopingAssistantOutput add = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add\",\"parent\":[\"FreeRTOS\",\"Praxis\"],\"name\":\"C/C++\"}}");
        ScopingAssistantOutput reread = parse(add.canonicalJson());
        assertEquals(Arrays.asList("FreeRTOS", "Praxis"), reread.getConceptAction().getParent());
        assertEquals("C/C++", reread.getConceptAction().getName());

        ScopingAssistantOutput remove = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"remove\",\"path\":[\"A\",\"B\"]}}");
        assertEquals(Arrays.asList("A", "B"),
                parse(remove.canonicalJson()).getConceptAction().getPath());
    }
}
