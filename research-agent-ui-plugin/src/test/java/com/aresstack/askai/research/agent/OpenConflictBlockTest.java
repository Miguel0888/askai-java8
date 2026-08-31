package com.aresstack.askai.research.agent;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GPT's gate-6 pin: the turn AFTER an exclusion-with-conflict must find the conflictId and the
 * exact resolve operation in its context — the model quotes, it never invents. This block is
 * appended verbatim to the fence, which is published before every model turn.
 */
public class OpenConflictBlockTest {

    @Test
    public void theBlockCarriesTheConflictIdTheCardAndTheExactResolveOperation() {
        Map<String, List<String>> conflicts = new LinkedHashMap<String, List<String>>();
        conflicts.put("conflict-1", Arrays.asList("RTOS-Grundlagen", "ESP-IDF"));
        String block = ResearchAgentSession.openConflictBlock(conflicts);

        assertTrue(block, block.contains("OPEN CONCEPT CONFLICT"));
        assertTrue(block, block.contains("conflictId \"conflict-1\""));
        assertTrue("the card name, not the full path — paths stay host-side",
                block.contains("card \"ESP-IDF\""));
        assertTrue(block, block.contains(
                "{\"type\": \"resolve\", \"conflictId\": \"<id from above>\", "
                        + "\"decision\": \"REMOVE\"}"));
        assertTrue(block, block.contains("KEEP_SUPPRESSED"));
        assertTrue("one decision, nothing else", block.contains("No other action in that turn"));
    }

    @Test
    public void withoutConflictsTheFenceStaysUntouched() {
        assertEquals("", ResearchAgentSession.openConflictBlock(
                new LinkedHashMap<String, List<String>>()));
    }
}
