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
        // Gate 2: the example must MODEL a valid id — the model copied the shape verbatim.
        assertTrue(with.contains("\"facetId\": \"esp-idf\""));
        assertTrue(with.contains("\"facetId\": \"arduino\""));
        // Gate 3: id shape spelled out, addFacet demands a human label, mission feeds the fence.
        assertTrue(with.contains("NEVER a sentence, a placeholder text or "));
        assertTrue(with.contains("addFacet ALWAYS carries BOTH"));
        assertTrue(with.contains("setMission as soon as it is stated"));
        // Gate 4 DECISION: an exclusion is ONE action on ONE channel — scope only, the concept
        // is never touched by it (the both-channels demand made the model bend exclusions onto
        // broader facets and use facet ids as concept paths).
        assertTrue(with.contains("An EXCLUSION is SCOPE-ONLY"));
        assertTrue(with.contains("Do NOT bend a newly named term onto a broader existing facet"));
        assertTrue(with.contains("an exclusion never touches the concept"));
        // Gate 4: the two identity spaces, pinned with the exact live confusion pair.
        assertTrue(with.contains("TWO IDENTITY SPACES"));
        assertTrue(with.contains("[\"ESP32 und FreeRTOS Setup\"]"));
        assertTrue(with.contains("\"esp32-setup\""));
        assertFalse("the both-channels demand is gone for good",
                with.contains("BOTH artifacts in the SAME answer"));
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
        // Scope hardening after the live gates: operations pin their kind AND a non-empty
        // facetId (gate 2: excludeFacet/addFacet arrived without one and the exclusion never
        // reached the Weidezaun); advisory suggestions must carry a NON-EMPTY label+query
        // (gate 1: an empty label once poisoned a whole scope turn).
        assertTrue(schema.contains("\"enum\":[\"setMission\",\"addFacet\",\"confirmFacet\","
                + "\"excludeFacet\""));
        // Gate 3: minLength alone let prompt placeholders become canonical facets — the id
        // shape is pinned in the grammar (and re-checked by the runtime for engines that
        // ignore 'pattern').
        assertTrue(schema.contains(
                "\"facetId\":{\"type\":\"string\",\"minLength\":1,"
                        + "\"pattern\":\"^[a-z0-9][a-z0-9_-]{0,63}$\"}"));
        assertTrue(schema.contains("\"required\":[\"kind\",\"facetId\"]"));
        assertTrue(schema.contains("\"required\":[\"label\",\"query\"]"));
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
