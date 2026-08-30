package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The small-model concept contract (K2c): tiny atomic actions addressed by name paths — no
 * handles, no branch payloads. {@code none}/absent both mean "this turn touches nothing"; a
 * malformed action never kills the turn (its reason goes back to the model with an example),
 * and the canonical history round-trips the action.
 */
public class ScopingConceptActionTest {

    private static ScopingAssistantOutput parse(String json) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(json);
        assertTrue("must parse: " + result.getError(), result.isOk());
        return result.getOutput();
    }

    @Test
    public void readAddAndRemoveParseWithTheirNamePaths() {
        ConceptAction read = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"read\",\"path\":\"FreeRTOS/Kommunikation\"}}").getConceptAction();
        assertEquals(ConceptAction.Type.READ, read.getType());
        assertEquals("FreeRTOS/Kommunikation", read.getPath());

        ConceptAction add = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add\",\"parent_path\":\"FreeRTOS\",\"name\":\"Synchronisation\"}}")
                .getConceptAction();
        assertEquals(ConceptAction.Type.ADD, add.getType());
        assertEquals("FreeRTOS", add.getParentPath());
        assertEquals("Synchronisation", add.getName());

        ConceptAction remove = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"remove\",\"path\":\"FreeRTOS/Praxis/ESP-IDF\"}}")
                .getConceptAction();
        assertEquals(ConceptAction.Type.REMOVE, remove.getType());
        assertEquals("FreeRTOS/Praxis/ESP-IDF", remove.getPath());
    }

    @Test
    public void noneAndAbsentBothMeanNoAction() {
        ScopingAssistantOutput none = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"none\"}}");
        assertNull(none.getConceptAction());
        assertNull(none.getConceptActionError());
        ScopingAssistantOutput absent = parse("{\"assistantMessage\":\"m\"}");
        assertNull(absent.getConceptAction());
        assertNull(absent.getConceptActionError());
        assertTrue("an action-free turn stays byte-identical to before",
                !absent.canonicalJson().contains("conceptAction"));
    }

    @Test
    public void aMalformedActionCarriesItsReasonWithAConcreteExample() {
        ScopingAssistantOutput missingName = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"add\",\"parent_path\":\"FreeRTOS\"}}");
        assertNull(missingName.getConceptAction());
        assertTrue(missingName.getConceptActionError().contains("requires \"name\""));
        assertTrue("the rejection teaches by example",
                missingName.getConceptActionError().contains("{\"type\":\"add\""));

        ScopingAssistantOutput unknownType = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"update\"}}");
        assertTrue(unknownType.getConceptActionError().contains("unknown type"));
    }

    @Test
    public void theCanonicalHistoryRoundTripsTheAction() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add\",\"parent_path\":\"FreeRTOS\",\"name\":\"Tasks\"}}");
        ScopingAssistantOutput reread = parse(output.canonicalJson());
        assertEquals(ConceptAction.Type.ADD, reread.getConceptAction().getType());
        assertEquals("FreeRTOS", reread.getConceptAction().getParentPath());
        assertEquals("Tasks", reread.getConceptAction().getName());

        ScopingAssistantOutput removed = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"remove\",\"path\":\"A/B\"}}");
        assertEquals("A/B", parse(removed.canonicalJson()).getConceptAction().getPath());
    }
}
