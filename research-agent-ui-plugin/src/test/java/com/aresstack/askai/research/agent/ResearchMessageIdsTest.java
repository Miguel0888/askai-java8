package com.aresstack.askai.research.agent;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Persisted message ids must be unique across RESTARTS of the same chat, not just within one run. The
 * per-session counters alone restart at 1, so the first message after a restart would reuse an id an older
 * persisted message already carries — and the phase journal, which is keyed by exactly that id, would
 * silently re-attribute the OLD message to the NEW phase.
 */
public class ResearchMessageIdsTest {

    @Test
    public void twoSessionRunsOfTheSameChatNeverProduceTheSameId() {
        ResearchMessageIds firstRun = new ResearchMessageIds();
        ResearchMessageIds secondRun = new ResearchMessageIds();

        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < 50; i++) {
            assertTrue("ids within a run are unique", ids.add(firstRun.next("user")));
            assertTrue("ids of a second run never collide with the first", ids.add(secondRun.next("user")));
            assertTrue(ids.add(firstRun.qualify("event-" + i)));
            assertTrue("the same foreign event id from another run stays distinct",
                    ids.add(secondRun.qualify("event-" + i)));
        }
    }

    @Test
    public void aQualifiedIdKeepsTheOriginalVisibleForDiagnostics() {
        ResearchMessageIds ids = new ResearchMessageIds("run1");
        assertEquals("run1-approval-7", ids.qualify("approval-7"));
        assertEquals("run1-user-1", ids.next("user"));
    }

    @Test
    public void anEmptyForeignIdStillYieldsAUsableUniqueId() {
        ResearchMessageIds ids = new ResearchMessageIds("run1");
        String first = ids.qualify("");
        String second = ids.qualify(null);
        assertFalse(first.equals(second));
        assertTrue(first, first.startsWith("run1-"));
    }
}
