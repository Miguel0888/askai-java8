package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The optional {@code conceptAction} of a scoping answer: parsed, validated (a malformed action
 * never kills the turn — its reason travels back to the model), and round-tripped through the
 * canonical history codec so later turns can SEE what the assistant did with the tool.
 */
public class ScopingConceptActionTest {

    private static ScopingAssistantOutput parse(String json) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(json);
        assertTrue("must parse: " + result.getError(), result.isOk());
        return result.getOutput();
    }

    @Test
    public void aReadActionParsesWithPathAndDepth() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"read\",\"path\":\"FreeRTOS/Synchronisation\",\"depth\":2}}");
        ConceptAction action = output.getConceptAction();
        assertEquals(ConceptAction.Type.READ, action.getType());
        assertEquals("FreeRTOS/Synchronisation", action.getPath());
        assertEquals(2, action.getDepth());
        assertNull(output.getConceptActionError());
    }

    @Test
    public void anUpdateActionAcceptsTheBranchAsStringOrEmbeddedObject() {
        ScopingAssistantOutput asString = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"update\",\"handle\":\"b-3\","
                + "\"branchJson\":\"{\\\"Sync\\\":[]}\"}}");
        assertEquals("{\"Sync\":[]}", asString.getConceptAction().getBranchJson());
        // Small models often embed the branch as a real JSON object — accepted and re-serialized;
        // the HOST's strict parser stays the authority over what is valid.
        ScopingAssistantOutput asObject = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"update\",\"handle\":\"b-3\","
                + "\"branchJson\":{\"Sync\":[{\"Mutex\":[]}]},\"allowRemovals\":true}}");
        assertEquals("{\"Sync\":[{\"Mutex\":[]}]}", asObject.getConceptAction().getBranchJson());
        assertTrue(asObject.getConceptAction().isAllowRemovals());
    }

    @Test
    public void aMalformedActionCarriesItsReasonInsteadOfKillingTheTurn() {
        ScopingAssistantOutput missingHandle = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"update\",\"branchJson\":\"{}\"}}");
        assertNull(missingHandle.getConceptAction());
        assertTrue(missingHandle.getConceptActionError().contains("handle"));

        ScopingAssistantOutput unknownType = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"replace\"}}");
        assertTrue(unknownType.getConceptActionError().contains("unknown type"));
    }

    @Test
    public void theCanonicalHistoryRoundTripsTheAction() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"update\",\"handle\":\"b-3\","
                + "\"branchJson\":\"{\\\"Sync\\\":[]}\",\"allowRemovals\":false}}");
        String canonical = output.canonicalJson();
        assertTrue(canonical.contains("\"conceptAction\""));
        ScopingAssistantOutput reread = parse(canonical);
        assertEquals(ConceptAction.Type.UPDATE, reread.getConceptAction().getType());
        assertEquals("b-3", reread.getConceptAction().getHandle());
        assertEquals("{\"Sync\":[]}", reread.getConceptAction().getBranchJson());
    }

    @Test
    public void aTurnWithoutAnActionStaysExactlyAsBefore() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\"}");
        assertNull(output.getConceptAction());
        assertNull(output.getConceptActionError());
        assertTrue("no conceptAction in the canonical history of an action-free turn",
                !output.canonicalJson().contains("conceptAction"));
    }
}
