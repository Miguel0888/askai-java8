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
        assertTrue(with.contains("{\"type\":\"add\",\"parent_path\":"));
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
