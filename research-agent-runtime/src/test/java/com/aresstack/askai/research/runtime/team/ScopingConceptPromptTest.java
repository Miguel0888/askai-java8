package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The concept block rides the capability flag — and ONLY the flag (old hosts stay byte-identical). */
public class ScopingConceptPromptTest {

    @Test
    public void theConceptBlockAppearsExactlyWhenTheHostOffersTheTools() {
        String with = TeamAgentPlaybook.scopingSystemPrompt(false, true);
        assertTrue(with.contains("conceptAction"));
        assertTrue(with.contains("WE ARE BUILDING A BOOK"));
        assertTrue(with.contains("THE CONCEPT (conceptAction):"));
        // The MainframeMate lesson, pinned: concrete examples live IN the contract text.
        assertTrue(with.contains("{\"type\":\"add\",\"parent\":"));
        assertTrue("segments, never slash paths", with.contains("NAME SEGMENTS"));
        // K2e: explicit user-command examples + read discipline + scope-decisions-into-workpiece.
        assertTrue(with.contains("Map an explicit user command DIRECTLY"));
        assertTrue(with.contains("Do not read unrelated branches"));
        assertTrue(with.contains("Never pretend an exclusion or focus is stored"));
        // The live-gate lesson: the both-channels rule needs a CONCRETE worked example — the model
        // translated "ESP-IDF nicht behandeln" into a concept remove and never fed the scope.
        assertTrue(with.contains("ESP-IDF möchte ich doch nicht behandeln"));
        assertTrue(with.contains("\"kind\": \"excludeFacet\""));
        assertTrue("an exclusion is scope food, not a remove of a card that never existed",
                with.contains("Do NOT translate an exclusion into a concept remove"));
        assertTrue(with.contains("Task Notifications"));
        assertTrue(with.contains("{\"type\":\"none\"}"));
        assertTrue("no handle field in the model contract", !with.contains("\"handle\""));
        assertTrue("no branch payloads in the model contract", !with.contains("branchJson"));
        String without = TeamAgentPlaybook.scopingSystemPrompt(false, false);
        assertFalse(without.contains("conceptAction"));
        assertEquals("the flagless overload is the old prompt, byte-identical",
                TeamAgentPlaybook.scopingSystemPrompt(false), without);
    }

    @Test
    public void theConceptContractPublishesTheGenerationTimeSchemaOnlyWithTheFlag() {
        String schema = new ScopingPhaseOutputContract(true).outputSchemaJson();
        assertTrue(schema.contains("\"enum\":[\"none\",\"read\",\"add\",\"remove\"]"));
        assertTrue("the action decision is always explicit",
                schema.contains("\"required\":[\"assistantMessage\",\"conceptAction\"]"));
        assertTrue("runaway lists are stopped by the grammar", schema.contains("maxItems"));
        // Scope hardening after the live gate: operations pin their kind, advisory suggestions
        // must carry a NON-EMPTY label+query (an empty label once poisoned a whole scope turn).
        assertTrue(schema.contains("\"enum\":[\"setMission\",\"addFacet\",\"confirmFacet\","
                + "\"excludeFacet\""));
        assertTrue(schema.contains("\"required\":[\"kind\"]"));
        assertTrue(schema.contains("\"required\":[\"label\",\"query\"]"));
        assertTrue(schema.contains("\"minLength\":1"));
        assertEquals("without the tools the long-standing schema-free behaviour stays",
                null, new ScopingPhaseOutputContract(false).outputSchemaJson());
        assertEquals(null, new ScopingPhaseOutputContract().outputSchemaJson());
    }

    @Test
    public void theRegistryHandsTheFlagThrough() {
        assertTrue(PhaseAssistantProfileRegistry.defaults(false, true)
                .forPhase("scoping").getSystemPrompt().contains("conceptAction"));
        assertFalse(PhaseAssistantProfileRegistry.defaults(false, false)
                .forPhase("scoping").getSystemPrompt().contains("conceptAction"));
    }
}
